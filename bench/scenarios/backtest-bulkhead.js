/**
 * L-04 backtest-bulkhead — ADR-049 실측
 *
 * 질문: CPU-heavy 백테스트가 api 프로세스 안에서 돌아도 조회 경로의 지연이 유지되는가?
 * (분석 경로를 별도 서비스로 격리해야 하는 "실측된 병목"이 있는지 — ADR-033 원칙)
 *
 * 두 시나리오가 동시에 돈다:
 *  - probes : 조회 경로(검색·스크리너·종목·캔들) 10 VU — p95를 본다
 *  - storm  : POST /api/backtest 20 VU 무한 반복 — backtestExecutor(core 2, max 4, queue 20)가 429로 밀어낸다
 * STORM=0 이면 probes만 돈다(대조군). 두 실행의 probes p95를 비교한다.
 *
 * 실행: k6 run --env BASE_URL=http://localhost:58080 --env STOCK_ID=2 --env STORM=1 bench/scenarios/backtest-bulkhead.js
 */
import http from "k6/http";
import { check } from "k6";
import { Trend, Counter } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const STOCK = __ENV.STOCK_ID || "2";
const STORM = __ENV.STORM !== "0";
const H = { "X-Bench": "true" };

const probeLat   = new Trend("probe_latency_ms", true);
const btLat      = new Trend("backtest_latency_ms", true);
const bt429      = new Counter("backtest_rejected_429");
const btOk       = new Counter("backtest_ok");

const scenarios = {
  probes: { executor: "constant-vus", vus: 10, duration: "60s", exec: "probes" },
};
if (STORM) scenarios.storm = { executor: "constant-vus", vus: Number(__ENV.STORM_VUS || 20), duration: "60s", exec: "storm", startTime: "5s" };

export const options = {
  scenarios,
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: { "probe_latency_ms": ["p(95)<300"] },
};

export function probes() {
  const urls = [
    `${BASE}/api/stocks/search?query=%EC%82%BC%EC%84%B1`,
    `${BASE}/api/screener?tab=realtime&market=KOSPI&sort=amount&limit=20`,
    `${BASE}/api/stocks/${STOCK}`,
    `${BASE}/api/stocks/${STOCK}/candles?interval=1m`,
  ];
  for (const u of urls) {
    const r = http.get(u, { headers: H });
    probeLat.add(r.timings.duration);
    check(r, { "probe 200": (x) => x.status === 200 });
  }
}

export function storm() {
  const body = JSON.stringify({ stockId: Number(STOCK), strategy: "MA_CROSSOVER", fromDate: "2025-09-01", toDate: "2026-09-01" });
  const r = http.post(`${BASE}/api/backtest`, body, { headers: Object.assign({ "Content-Type": "application/json" }, H) });
  if (r.status === 429) bt429.add(1);
  else if (r.status === 200) { btOk.add(1); btLat.add(r.timings.duration); }
}

export function handleSummary(data) {
  const ts = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  const dir = __ENV.RESULT_DIR || "bench/results";
  const tag = `backtest-bulkhead-${STORM ? "storm" : "control"}`;
  return {
    [`${dir}/${tag}_${ts}.json`]: JSON.stringify(data, null, 2),
    [`${dir}/latest_${tag}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: "  ", enableColors: true }),
  };
}
