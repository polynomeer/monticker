/**
 * L-02 ws-fanout — resilience-plan §5.1 / ADR-045
 *
 * STOMP over raw WebSocket. Spring의 withSockJS()는 /ws/websocket 에 raw 트랜스포트도 노출하므로
 * SockJS 핸드셰이크 없이 STOMP 프레임(CONNECT/SUBSCRIBE/MESSAGE)을 직접 주고받는다.
 * (프로덕션 클라이언트는 SockJS 경유 — 그 경로의 특성은 별도 확인, ADR-045 §2 주석)
 *
 * 측정:
 *  - ws_messages            수신 MESSAGE 프레임 수 (fan-out 처리량)
 *  - ws_e2e_latency_ms      틱 tradeTime → 브라우저 수신까지 (같은 머신이라 시계 동일)
 *  - ws_per_conn_msg_rate   연결당 초당 메시지 수 — ADR-038 conflation 전/후 비교의 핵심 수치
 * 옵션:
 *  - SUBS=20     연결당 구독 종목 수 (사용자 관심종목 평균 가정)
 *  - GLOBAL=1    /topic/market(전역 브로드캐스트, ADR-039가 폐지 예정)도 구독 — "before" 측정용
 *  - HOLD=60     연결 유지 초
 * 실행: k6 run --env SCENARIO=baseline --env BASE_URL=http://localhost:58080 --env GLOBAL=1 bench/scenarios/ws-fanout.js
 */
import ws from "k6/ws";
import { check } from "k6";
import { Counter, Trend, Rate } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

const msgs        = new Counter("ws_messages");
const globalMsgs  = new Counter("ws_messages_global_topic");
const latency     = new Trend("ws_e2e_latency_ms", true);
const connectOk   = new Rate("ws_connect_ok");
const stompOk     = new Rate("ws_stomp_connected");
const perConnRate = new Trend("ws_per_conn_msg_rate");
const parseErr    = new Counter("ws_parse_errors");

const SCENARIO = __ENV.SCENARIO || "smoke";
const HOLD     = Number(__ENV.HOLD || 60);
const SUBS     = Number(__ENV.SUBS || 20);
const GLOBAL   = __ENV.GLOBAL === "1";
const BASE     = (__ENV.BASE_URL || "http://localhost:8080").replace(/^http/, "ws");
const STOCK_IDS = Array.from({ length: 200 }, (_, i) => i + 2);   // V12 시드 202종목, id 2~

const SCENARIOS = {
  smoke:    { stages: [{ duration: "10s", target: 5 },   { duration: `${HOLD}s`, target: 5 },   { duration: "5s", target: 0 }] },
  // L-02: 50 → 200 → 500 연결 (§5.1은 100→1,000→5,000 — 로컬 한 대에서 k6와 api가 같이 돌므로 축소)
  baseline: { stages: [{ duration: "20s", target: 50 },  { duration: `${HOLD}s`, target: 50 },
                       { duration: "20s", target: 200 }, { duration: `${HOLD}s`, target: 200 },
                       { duration: "30s", target: 500 }, { duration: `${HOLD}s`, target: 500 }, { duration: "10s", target: 0 }] },
};
export const options = {
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],   // SLO는 p99를 본다 — 기본 요약에 없다
  stages: SCENARIOS[SCENARIO].stages,
  thresholds: {
    "ws_connect_ok":       ["rate>0.99"],
    "ws_stomp_connected":  ["rate>0.99"],
    "ws_e2e_latency_ms":   ["p(99)<300"],      // §4.3 실시간 SLO
    "ws_parse_errors":     ["count<10"],
  },
};

const NUL = String.fromCharCode(0);
const frame = (cmd, headers, body = "") =>
  cmd + "\n" + Object.entries(headers).map(([k, v]) => `${k}:${v}`).join("\n") + "\n\n" + body + NUL;

export default function () {
  const start = Date.now();
  let received = 0, connected = false;
  // VU마다 다른 종목 구간을 구독 — 실제로도 사용자마다 관심종목이 다르다. 상위 종목 편중은 별도 시나리오.
  const mine = Array.from({ length: SUBS }, (_, i) => STOCK_IDS[(__VU * 7 + i) % STOCK_IDS.length]);

  const res = ws.connect(`${BASE}/ws/websocket`, {}, (socket) => {
    socket.on("open", () => socket.send(frame("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0" })));
    socket.on("message", (data) => {
      // 한 WS 메시지에 STOMP 프레임이 여러 개 올 수 있다 — NUL로 나눈다
      for (const f of String(data).split(NUL)) {
        if (!f.trim()) continue;
        if (f.startsWith("CONNECTED")) {
          connected = true; stompOk.add(true);
          mine.forEach((id, i) => socket.send(frame("SUBSCRIBE", { id: `s${i}`, destination: `/topic/stocks/${id}` })));
          if (GLOBAL) socket.send(frame("SUBSCRIBE", { id: "g", destination: "/topic/market" }));
        } else if (f.startsWith("MESSAGE")) {
          received++; msgs.add(1);
          const isGlobal = /destination:\/topic\/market\n/.test(f);
          if (isGlobal) globalMsgs.add(1);
          const body = f.slice(f.indexOf("\n\n") + 2);
          try {
            const ts = Date.parse(JSON.parse(body).timestamp);
            if (!isNaN(ts)) latency.add(Date.now() - ts);
          } catch (e) { parseErr.add(1); }
        } else if (f.startsWith("ERROR")) {
          stompOk.add(false);
        }
      }
    });
    socket.on("error", () => connectOk.add(false));
    socket.setTimeout(() => { socket.send(frame("DISCONNECT", {})); socket.close(); }, HOLD * 1000);
  });
  connectOk.add(!!res && res.status === 101);
  if (!connected) stompOk.add(false);
  const secs = (Date.now() - start) / 1000;
  if (secs > 5) perConnRate.add(received / secs);
  check(res, { "ws 101": (r) => !!r && r.status === 101 });
}

export function handleSummary(data) {
  const ts = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  const dir = __ENV.RESULT_DIR || "bench/results";
  const tag = `ws-${SCENARIO}${GLOBAL ? "-global" : ""}`;
  return {
    [`${dir}/${tag}_${ts}.json`]: JSON.stringify(data, null, 2),
    [`${dir}/latest_${tag}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: "  ", enableColors: true }),
  };
}
