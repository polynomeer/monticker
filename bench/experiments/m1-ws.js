/**
 * M-001(a) WS push — N개의 STOMP/WebSocket 클라이언트가 각자 관심종목 SUBS개를 구독하고 HOLD초 동안 틱을 받는다.
 * 측정: tick→client 지연(ms) = 수신 시각 − 메시지의 timestamp(틱 tradeTime; 같은 머신이라 시계가 같다),
 *       연결 성공률, 연결당 수신 메시지, 메시지 사이 최대 침묵(ms; M-001(b) Kafka 정지 실험에서 "끊긴 시간").
 * 서버 쪽 지연은 PriceBroadcaster의 100ms conflation(ADR-038)을 포함한다 — 그게 실제 사용자가 보는 지연이다.
 * 실행: k6 run --env N=1000 --env HOLD=60 --env SUBS=5 --env BASE_URL=http://localhost:58080 bench/experiments/m1-ws.js
 */
import ws from "k6/ws";
import { Counter, Trend, Rate, Gauge } from "k6/metrics";

const N = Number(__ENV.N || 1000), HOLD = Number(__ENV.HOLD || 60), SUBS = Number(__ENV.SUBS || 5);
const RAMP = Number(__ENV.RAMP || Math.max(10, Math.round(N / 200)));   // 초당 ~200 연결로 램프
const BASE = (__ENV.BASE_URL || "http://localhost:8080").replace(/^http/, "ws");
const STOCK_IDS = Array.from({ length: 202 }, (_, i) => i + 2);

const latency = new Trend("ws_tick_to_client_ms", true);
const msgs = new Counter("ws_messages");
const connectOk = new Rate("ws_connect_ok");
const stompOk = new Rate("ws_stomp_connected");
const silence = new Trend("ws_max_silence_ms", true);
const perConn = new Trend("ws_msgs_per_conn");
const errors = new Counter("ws_errors");

export const options = {
  scenarios: { clients: { executor: "ramping-vus", startVUs: 0,
    stages: [{ duration: `${RAMP}s`, target: N }, { duration: `${HOLD}s`, target: N }, { duration: "5s", target: 0 }] } },
  summaryTrendStats: ["min", "med", "p(95)", "p(99)", "max"],
  thresholds: { ws_connect_ok: ["rate>0.99"], ws_stomp_connected: ["rate>0.99"] },
};
const NUL = String.fromCharCode(0);
const frame = (cmd, headers, body = "") =>
  cmd + "\n" + Object.entries(headers).map(([k, v]) => `${k}:${v}`).join("\n") + "\n\n" + body + NUL;

export default function () {
  const mine = Array.from({ length: SUBS }, (_, i) => STOCK_IDS[(__VU * 7 + i) % STOCK_IDS.length]);
  let received = 0, last = 0, maxGap = 0, connected = false;
  const res = ws.connect(`${BASE}/ws/websocket`, {}, (socket) => {
    socket.on("open", () => socket.send(frame("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0" })));
    socket.on("message", (data) => {
      for (const f of String(data).split(NUL)) {
        if (!f.trim()) continue;
        if (f.startsWith("CONNECTED")) {
          connected = true; stompOk.add(true);
          mine.forEach((id, i) => socket.send(frame("SUBSCRIBE", { id: `s${i}`, destination: `/topic/stocks/${id}` })));
        } else if (f.startsWith("MESSAGE")) {
          const now = Date.now();
          if (last) maxGap = Math.max(maxGap, now - last);
          last = now; received++; msgs.add(1);
          const m = /"timestamp":"([^"]+)"/.exec(f);
          if (m) latency.add(now - Date.parse(m[1]));
        } else if (f.startsWith("ERROR")) { errors.add(1); }
      }
    });
    socket.on("error", () => errors.add(1));
    // 램프 중 먼저 붙은 연결도 HOLD 끝까지 유지 — VU가 정해진 시간 뒤 닫는다
    socket.setTimeout(() => socket.close(), (HOLD + RAMP) * 1000);
  });
  const ok = res && res.status === 101;
  connectOk.add(ok);
  if (!connected) stompOk.add(false);
  if (ok) { perConn.add(received); silence.add(maxGap); }
}
