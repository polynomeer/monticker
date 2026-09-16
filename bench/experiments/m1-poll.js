/**
 * M-001(a) REST polling — N개의 클라이언트가 각자 관심종목 SUBS개를 INTERVAL_MS 마다 GET /api/stocks/{id}/price 로 읽는다.
 * 클라이언트 N개를 VU N개로 흉내 내지 않고 도착률로 모델링한다: 요청률 = N × SUBS / INTERVAL_MS (constant-arrival-rate).
 * 서버가 그 요청률을 못 받으면 dropped_iterations 가 쌓인다 — 그게 "이 N은 유지 불가"의 증거다.
 * 측정: http 응답 지연, 그리고 staleness(ms) = 응답 수신 시각 − 응답의 tradeTime (사용자가 보는 값이 얼마나 오래된 것인가;
 *       WS 의 tick→client 지연과 같은 축이다 — 폴링은 여기에 평균 INTERVAL/2 가 구조적으로 더해진다).
 * 실행: k6 run --env N=1000 --env INTERVAL_MS=1000 --env HOLD=60 --env BASE_URL=http://localhost:58080 bench/experiments/m1-poll.js
 */
import http from "k6/http";
import { Trend, Counter, Rate } from "k6/metrics";

const N = Number(__ENV.N || 1000), HOLD = Number(__ENV.HOLD || 60), SUBS = Number(__ENV.SUBS || 5);
const INTERVAL = Number(__ENV.INTERVAL_MS || 1000);
const BASE = __ENV.BASE_URL || "http://localhost:8080";
const STOCK_IDS = Array.from({ length: 202 }, (_, i) => i + 2);
const RATE = Math.round((N * SUBS * 1000) / INTERVAL);          // req/s

const staleness = new Trend("poll_staleness_ms", true);
const ok = new Rate("poll_ok");
const noData = new Counter("poll_no_data");

export const options = {
  scenarios: { poll: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: `${HOLD}s`,
    preAllocatedVUs: Math.min(500, Math.max(50, Math.round(RATE / 20))), maxVUs: Number(__ENV.MAX_VUS || 3000) } },
  summaryTrendStats: ["min", "med", "p(95)", "p(99)", "max"],
  thresholds: { poll_ok: ["rate>0.99"] },
};

export default function () {
  const id = STOCK_IDS[(__VU * 7 + __ITER) % STOCK_IDS.length];
  // X-Bench: /api/** 는 IP당 300/분 레이트리밋(RateLimitFilter) — local 프로파일만 이 헤더로 우회한다. 운영은 우회 불가.
  const r = http.get(`${BASE}/api/stocks/${id}/price`, { headers: { "X-Bench": "true" }, tags: { name: "price" } });
  const good = r.status === 200;
  ok.add(good);
  if (good) {
    const m = /"tradeTime":"([^"]+)"/.exec(r.body);
    if (m) staleness.add(Date.now() - Date.parse(m[1])); else noData.add(1);
  }
}
