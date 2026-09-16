#!/usr/bin/env node
/** M-001(b) 관측 연결 — STOMP/WS 1개로 종목 몇 개를 구독하고 메시지마다 "수신ms tradeTimeMs stockId" 를 찍는다.
 *  Kafka 정지 → 복구 실험에서 "마지막 수신 시각 / 재개 시각"을 밀리초로 잡는 용도. 실행: node m1-observer.js ws://host:port 초 */
const url = process.argv[2] || "ws://localhost:8080", secs = +process.argv[3] || 120;
const NUL = "\0", frame = (c, h) => c + "\n" + Object.entries(h).map(([k, v]) => `${k}:${v}`).join("\n") + "\n\n" + NUL;
const ws = new WebSocket(url + "/ws/websocket");
ws.onopen = () => ws.send(frame("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0" }));
ws.onmessage = (ev) => {
  for (const f of String(ev.data).split(NUL)) {
    if (f.startsWith("CONNECTED")) for (const id of [2, 3, 4, 5, 6]) ws.send(frame("SUBSCRIBE", { id: `s${id}`, destination: `/topic/stocks/${id}` }));
    else if (f.startsWith("MESSAGE")) { const m = /"timestamp":"([^"]+)"/.exec(f), s = /"stockId":(\d+)/.exec(f); console.log(Date.now(), m ? Date.parse(m[1]) : 0, s ? s[1] : 0); }
  }
};
ws.onclose = () => console.error("closed", Date.now());
setTimeout(() => process.exit(0), secs * 1000);
