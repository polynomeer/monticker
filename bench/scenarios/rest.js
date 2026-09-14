/**
 * L-01 rest-baseline — resilience-plan §5.1 / ADR-045
 *
 * 기존 api.js와의 차이:
 *  - 임계값을 §4.3 조회 SLO(p95 200ms, p99 500ms, 에러 0.1%)에 맞춘다. 두 곳에 다른 숫자가 있으면 안 된다.
 *  - 인증 경로(지갑·주문 목록)와 주문 접수(POST /api/brokerage/orders)를 포함한다 — 거래 경로가 빠진 기준선은 기준선이 아니다.
 *  - 프로파일 이름을 §5.1과 맞춘다: smoke | baseline(L-01) | stress | spike
 *
 * 실행: BASE_URL=http://localhost:58080 k6 run --env SCENARIO=baseline bench/scenarios/rest.js
 * 로컬 수치는 상대 비교용이다(전/후). 절대값·SLO 판정은 전용 환경에서만 (ADR-045 §5).
 */
import http from "k6/http";
import { check, sleep, group } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

const d = (n) => new Trend(n, true);
const T = {
  search:   d("rest_search_ms"),
  screener: d("rest_screener_ms"),
  detail:   d("rest_stock_detail_ms"),
  candles:  d("rest_candles_ms"),
  events:   d("rest_events_ms"),
  wallet:   d("rest_wallet_ms"),
  orders:   d("rest_orders_list_ms"),
  order:    d("rest_order_submit_ms"),
};
const orderPath5xx = new Rate("order_path_5xx");     // 주문 경로 5xx 비율 — OrderPathDown 알람과 같은 정의
const errorCount   = new Counter("error_count");

const SCENARIO = __ENV.SCENARIO || "smoke";
const SLO = { "http_req_failed": ["rate<0.001"], "http_req_duration": ["p(95)<200", "p(99)<500"], "order_path_5xx": ["rate<0.001"] };
const SCENARIOS = {
  smoke:    { stages: [{ duration: "30s", target: 3 }, { duration: "30s", target: 0 }], thresholds: SLO },
  // L-01: 10 → 50 → 100 VU. §5.1은 각 3분이지만 로컬 상대 비교는 1분씩으로 충분하다.
  baseline: { stages: [{ duration: "1m", target: 10 }, { duration: "1m", target: 50 }, { duration: "1m", target: 100 }, { duration: "20s", target: 0 }], thresholds: SLO },
  stress:   { stages: [{ duration: "1m", target: 50 }, { duration: "1m", target: 150 }, { duration: "1m", target: 300 }, { duration: "30s", target: 0 }],
              thresholds: { "http_req_failed": ["rate<0.05"], "http_req_duration": ["p(95)<1000"] } },
  spike:    { stages: [{ duration: "10s", target: 5 }, { duration: "10s", target: 200 }, { duration: "30s", target: 200 }, { duration: "10s", target: 5 }, { duration: "10s", target: 0 }],
              thresholds: { "http_req_failed": ["rate<0.10"] } },
};
export const options = {
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],   // SLO는 p99를 본다 — 기본 요약에 없다 stages: SCENARIOS[SCENARIO].stages, thresholds: SCENARIOS[SCENARIO].thresholds };

const BASE      = __ENV.BASE_URL || "http://localhost:8080";
const USERS     = Number(__ENV.USERS || 20);
const QUERIES   = ["005930", "000660", "NVDA", "AAPL", "035420", "%EC%82%BC%EC%84%B1"];
const SORTS     = ["amount", "volume", "rise", "fall"];
const MARKETS   = ["all", "domestic", "overseas"];
const STOCK_IDS = [2, 3, 4, 5, 6, 7, 8];
const rand = (a) => a[Math.floor(Math.random() * a.length)];
const H = { "X-Bench": "true" };   // IP 레이트리밋 우회 — local/dev 프로파일에서만 유효(P0-4)
// 404(없는 종목)·409(계좌 미연결 등 비즈니스 거절)·422(리스크 거부)는 정상 응답이다. 429는 실패로 남긴다 —
// userId 레이트리밋(@RateLimited)에 걸리면 부하 프로파일이 잘못된 것이므로 보여야 한다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 404, 409, 422));

// 인증 경로용 계정을 미리 만든다. signup은 토큰을 바로 돌려준다.
export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const r = http.post(`${BASE}/api/auth/signup`, JSON.stringify({
      email: `bench-${Date.now()}-${i}@test.local`, password: "password123", nickname: `bench${i}` }),
      { headers: Object.assign({ "Content-Type": "application/json" }, H) });
    if (r.status === 200) tokens.push(JSON.parse(r.body).accessToken);
  }
  if (tokens.length === 0) throw new Error("setup: 계정 생성 실패 — API/레이트리밋 확인");
  return { tokens };
}

function hit(name, res, okStatus, trend) {
  const ok = okStatus.includes(res.status);
  trend.add(res.timings.duration);
  if (!ok) errorCount.add(1);
  check(res, { [`${name} ok`]: () => ok });
  return ok;
}

export default function (data) {
  const token = data.tokens[__VU % data.tokens.length];
  const auth  = Object.assign({ Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, H);
  http.cookieJar().clear(BASE);

  group("조회", () => {
    hit("search",   http.get(`${BASE}/api/stocks/search?query=${rand(QUERIES)}`, { headers: H }), [200], T.search);
    hit("screener", http.get(`${BASE}/api/screener?tab=realtime&market=${rand(MARKETS)}&sort=${rand(SORTS)}&limit=20`, { headers: H }), [200], T.screener);
    const id = rand(STOCK_IDS);
    hit("detail",   http.get(`${BASE}/api/stocks/${id}`, { headers: H }), [200, 404], T.detail);
    hit("candles",  http.get(`${BASE}/api/stocks/${id}/candles?interval=1m`, { headers: H }), [200], T.candles);
    hit("events",   http.get(`${BASE}/api/stocks/${id}/events`, { headers: H }), [200], T.events);
  });
  sleep(0.2);
  group("거래 (인증)", () => {
    hit("wallet",  http.get(`${BASE}/api/wallet`, { headers: auth }), [200], T.wallet);
    hit("orders",  http.get(`${BASE}/api/brokerage/orders`, { headers: auth }), [200], T.orders);
    // 브로커 계좌 미연결이라 409가 정상. 5xx만이 실패다 — 주문 경로가 "응답했는가"를 본다.
    // 주문은 userId당 30회/분 제한이 있으므로(POST /api/brokerage/orders @RateLimited) 20% 확률로만 제출한다 —
    // 실제 사용자도 매 페이지 조회마다 주문하지 않는다.
    if (Math.random() >= 0.2) return;
    const r = http.post(`${BASE}/api/brokerage/orders`,
      JSON.stringify({ symbol: "005930", side: "BUY", orderType: "MARKET", quantity: 1 }),
      { headers: Object.assign({ "X-Idempotency-Key": `b-${__VU}-${__ITER}-${Date.now()}` }, auth) });
    orderPath5xx.add(r.status >= 500);
    hit("order", r, [200, 201, 202, 409, 422], T.order);
  });
  sleep(0.5);
}

export function handleSummary(data) {
  const ts = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  const dir = __ENV.RESULT_DIR || "bench/results";
  return {
    [`${dir}/rest-${SCENARIO}_${ts}.json`]: JSON.stringify(data, null, 2),
    [`${dir}/latest_rest-${SCENARIO}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: "  ", enableColors: true }),
  };
}
