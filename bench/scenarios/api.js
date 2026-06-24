/**
 * monticker API 벤치마크
 * 실행: k6 run bench/scenarios/api.js
 * 리포트: k6 run --out json=bench/results/latest.json bench/scenarios/api.js
 */
import http from "k6/http";
import { check, sleep, group } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { htmlReport } from "https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

// ── 커스텀 메트릭 ──────────────────────────────────────────────
const screenerDuration = new Trend("screener_duration", true);
const stocksDuration   = new Trend("stocks_duration",   true);
const eventsDuration   = new Trend("events_duration",   true);
const recentDuration   = new Trend("recent_events_duration", true);
const errorCount       = new Counter("error_count");
const successRate      = new Rate("success_rate");

// ── 시나리오 설정 (환경 변수로 오버라이드 가능) ─────────────────
const SCENARIO = __ENV.SCENARIO || "smoke"; // smoke | load | stress | spike

const SCENARIOS = {
  smoke: {
    // 기본 동작 확인 (1분)
    stages: [
      { duration: "30s", target: 3 },
      { duration: "30s", target: 0 },
    ],
    thresholds: {
      "http_req_failed":      ["rate<0.01"],
      "screener_duration":    ["p(95)<500"],
      "stocks_duration":      ["p(95)<300"],
    },
  },
  load: {
    // 일반 부하 테스트 (3분)
    stages: [
      { duration: "30s", target: 10  },
      { duration: "1m",  target: 30  },
      { duration: "30s", target: 50  },
      { duration: "1m",  target: 50  },
      { duration: "30s", target: 0   },
    ],
    thresholds: {
      "http_req_failed":      ["rate<0.01"],
      "http_req_duration":    ["p(95)<300", "p(99)<500"],
      "screener_duration":    ["p(95)<200"],
      "stocks_duration":      ["p(95)<150"],
      "events_duration":      ["p(95)<150"],
    },
  },
  stress: {
    // 스트레스 테스트 (5분, 피크 100 VUs)
    stages: [
      { duration: "1m",  target: 20  },
      { duration: "1m",  target: 50  },
      { duration: "1m",  target: 100 },
      { duration: "1m",  target: 100 },
      { duration: "1m",  target: 0   },
    ],
    thresholds: {
      "http_req_failed":      ["rate<0.05"],
      "http_req_duration":    ["p(95)<1000"],
    },
  },
  spike: {
    // 스파이크 테스트 (갑작스러운 부하)
    stages: [
      { duration: "10s", target: 5   },
      { duration: "10s", target: 100 },  // 급격히 증가
      { duration: "30s", target: 100 },
      { duration: "10s", target: 5   },  // 급격히 감소
      { duration: "10s", target: 0   },
    ],
    thresholds: {
      "http_req_failed":      ["rate<0.10"],
    },
  },
};

export const options = {
  stages:     SCENARIOS[SCENARIO].stages,
  thresholds: SCENARIOS[SCENARIO].thresholds,
  // Rate limit 우회: IP당 요청 헤더에 X-Bench 표시
  // (서버 측에서 벤치마크 요청은 rate limit 제외 가능)
};

// ── 설정 ────────────────────────────────────────────────────────
const BASE      = __ENV.BASE_URL || "http://localhost:8080";
const QUERIES   = ["005930", "000660", "NVDA", "AAPL", "035420"];
const SORTS     = ["amount", "volume", "rise", "fall"];
const MARKETS   = ["all", "domestic", "overseas"];
const STOCK_IDS = [2, 3, 4, 5, 6];

function rand(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

const HEADERS = { "X-Bench": "true" };

// ── 메인 테스트 ─────────────────────────────────────────────────
export default function () {
  // 매 iteration 쿠키 초기화 (인증 쿠키 오염 방지)
  http.cookieJar().clear(BASE);

  let allOk = true;

  group("종목 검색", () => {
    const res = http.get(`${BASE}/api/stocks/search?query=${rand(QUERIES)}`, { headers: HEADERS });
    const ok = check(res, {
      "status 200":     r => r.status === 200,
      "응답시간 < 300ms": r => r.timings.duration < 300,
    });
    stocksDuration.add(res.timings.duration);
    if (!ok) { errorCount.add(1); allOk = false; }
  });

  sleep(0.1);

  group("스크리너", () => {
    const res = http.get(
      `${BASE}/api/screener?tab=realtime&market=${rand(MARKETS)}&sort=${rand(SORTS)}&limit=20`,
      { headers: HEADERS }
    );
    const ok = check(res, {
      "status 200":     r => r.status === 200,
      "응답시간 < 200ms": r => r.timings.duration < 200,
      "items 존재":     r => { try { return Array.isArray(JSON.parse(r.body).items); } catch { return false; } },
    });
    screenerDuration.add(res.timings.duration);
    if (!ok) { errorCount.add(1); allOk = false; }
  });

  sleep(0.1);

  group("이벤트 타임라인", () => {
    const res = http.get(`${BASE}/api/stocks/${rand(STOCK_IDS)}/events`, { headers: HEADERS });
    const ok = check(res, {
      "status 200":     r => r.status === 200,
      "응답시간 < 200ms": r => r.timings.duration < 200,
    });
    eventsDuration.add(res.timings.duration);
    if (!ok) { errorCount.add(1); allOk = false; }
  });

  sleep(0.1);

  group("최근 이벤트 (홈)", () => {
    const res = http.get(`${BASE}/api/events/recent?limit=10`, { headers: HEADERS });
    const ok = check(res, { "status 200": r => r.status === 200 });
    recentDuration.add(res.timings.duration);
    if (!ok) { errorCount.add(1); allOk = false; }
  });

  successRate.add(allOk);
  sleep(0.6);
}

// ── 리포트 생성 ─────────────────────────────────────────────────
export function handleSummary(data) {
  const ts        = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  const scenario  = __ENV.SCENARIO || "smoke";
  const reportDir = __ENV.REPORT_DIR || "bench/reports";
  const resultDir = __ENV.RESULT_DIR || "bench/results";

  return {
    // HTML 리포트
    [`${reportDir}/${scenario}_${ts}.html`]: htmlReport(data),
    // JSON 원본 데이터
    [`${resultDir}/${scenario}_${ts}.json`]: JSON.stringify(data, null, 2),
    // 최신 결과 (항상 latest로 덮어씀)
    [`${resultDir}/latest_${scenario}.json`]: JSON.stringify(data, null, 2),
    // 터미널 출력
    stdout: textSummary(data, { indent: "  ", enableColors: true }),
  };
}
