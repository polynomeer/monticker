/**
 * L-05 order-burst — 동시 주문 부하 하에서 자금 정합성(잔고·체결·Saga)을 검증하기 위한 부하 생성기 (ADR-045 §3·§5 거래 클래스).
 * 성능이 아니라 정합성이 본체다: 이 스크립트로 주문을 몰아친 뒤 bench/consistency/verify.py 로 불변식을 대조한다.
 *
 * 각 VU = 독립 계정(email burst-<RUN>-<vu>@bench.local, X-Bench 로 레이트리밋 우회 — local 프로파일 전용).
 * 반복마다 시드 종목에 시장가 주문: 기본 BUY, 5회마다 SELL(보유분 회수 — 없으면 4xx, 정합성 위반 아님).
 * 주문당 X-Idempotency-Key 로 중복 제출 방지. 계정 현금이 마르면 주문이 거절되지만(정합성 위반 아님) 계속 시도한다.
 * 실행: k6 run --env RUN=$(date +%s) --env VUS=100 --env DURATION=60s --env BASE_URL=http://localhost:58080 bench/scenarios/order-burst.js
 * RUN 값을 verify.py 에 넘겨 대상 계정을 좁힌다: python3 bench/consistency/verify.py "burst-<RUN>-%@bench.local"
 */
import http from "k6/http";
import { sleep } from "k6";
import { Counter, Rate } from "k6/metrics";

const RUN = __ENV.RUN || "0";
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || "60s";
const BASE = __ENV.BASE_URL || "http://localhost:8080";
// 시드 종목 id 는 연속이 아니다(202종목이 2~207에 구멍 있게 분포). STOCK_IDS 로 실제 id 목록을 넘긴다 — 없으면 안전한 기본.
const STOCK_IDS = (__ENV.STOCK_IDS || "2,3,4,5,6,9,10,11,12,13").split(",").map(Number);
const SLEEP = Number(__ENV.SLEEP || 2.5);   // 계정당 주문 간격 — @RateLimited(30/60s) 밑으로 맞춘다(24/min)

const submitted = new Counter("orders_submitted");   // HTTP 200 (체결/접수)
const filled = new Counter("orders_filled");
const rejected = new Counter("orders_rejected");      // 4xx (현금 부족·보유 없음 등 — 정합성 위반 아님)
const errors = new Counter("orders_error");           // 5xx
const okRate = new Rate("order_ok");

export const options = {
  scenarios: { burst: { executor: "ramping-vus", startVUs: 0,
    stages: [{ duration: "15s", target: VUS }, { duration: DURATION, target: VUS }, { duration: "5s", target: 0 }] } },
  thresholds: { order_ok: ["rate>0.0"] },   // 정합성이 본체라 성능 임계는 느슨하게
};

const tokens = {};
function token() {
  if (tokens[__VU]) return tokens[__VU];
  const email = `burst-${RUN}-${__VU}@bench.local`;
  const r = http.post(`${BASE}/api/auth/signup`, JSON.stringify({ email, password: "password123", nickname: `b${__VU}` }),
    { headers: { "Content-Type": "application/json", "X-Bench": "true" } });
  try { tokens[__VU] = JSON.parse(r.body).accessToken; } catch (e) { tokens[__VU] = null; }
  return tokens[__VU];
}

export default function () {
  const t = token();
  if (!t) { errors.add(1); return; }
  // VU 하나가 고정 종목 하나만 거래 — SELL 이 실제 보유분에 걸려 체결되도록(양방향 자금 경로 검증). 3회 BUY 마다 1회 SELL.
  const stockId = STOCK_IDS[__VU % STOCK_IDS.length];
  const side = (__ITER % 4 === 3) ? "SELL" : "BUY";
  const r = http.post(`${BASE}/api/matching/orders`,
    JSON.stringify({ stockId, side, orderType: "MARKET", quantity: 1 }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${t}`,
                 "X-Bench": "true", "X-Idempotency-Key": `burst-${RUN}-${__VU}-${__ITER}` },
      tags: { name: "order" } });
  okRate.add(r.status === 200);
  if (r.status === 200) { submitted.add(1); if (/"status":"FILLED"/.test(r.body)) filled.add(1); }
  else if (r.status >= 500) errors.add(1);
  else rejected.add(1);
  sleep(SLEEP);
}
