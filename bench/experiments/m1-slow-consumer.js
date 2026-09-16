#!/usr/bin/env node
/**
 * M-001(c) 느린 소비자 — STOMP/WS 연결 K개를 열어 202종목을 REPEAT번씩 구독한 뒤 소켓 읽기를 멈춘다(socket.pause()).
 * 커널 수신 버퍼가 차면 서버의 쓰기가 막히고, Spring의 ConcurrentWebSocketSessionDecorator 한도(기본 sendTimeLimit 10s,
 * sendBufferSizeLimit 512KB)가 세션을 끊는지, 그때까지 다른 클라이언트가 영향을 받는지가 질문이다.
 * 고수준 WebSocket API는 읽기를 멈출 수 없어 net.Socket 위에 핸드셰이크·프레임(마스킹)을 직접 쓴다.
 * 출력: 한 줄 JSON {ports:[로컬포트…]} — 드라이버가 netstat 로 각 연결의 TCP 상태(ESTABLISHED→CLOSE_WAIT)를 추적한다.
 * 실행: node bench/experiments/m1-slow-consumer.js <host> <port> <K> <REPEAT> <HOLD초>
 */
const net = require("net"), crypto = require("crypto");
const [host, port, K, REPEAT, HOLD] = [process.argv[2] || "localhost", +process.argv[3] || 8080, +process.argv[4] || 1, +process.argv[5] || 1, +process.argv[6] || 60];
const NUL = "\0";
const frame = (cmd, h, body = "") => cmd + "\n" + Object.entries(h).map(([k, v]) => `${k}:${v}`).join("\n") + "\n\n" + body + NUL;
function wsText(s) {   // 클라이언트→서버 텍스트 프레임(마스킹 필수)
  const p = Buffer.from(s), mask = crypto.randomBytes(4);
  let head;
  if (p.length < 126) head = Buffer.from([0x81, 0x80 | p.length]);
  else { head = Buffer.alloc(4); head[0] = 0x81; head[1] = 0x80 | 126; head.writeUInt16BE(p.length, 2); }
  const m = Buffer.alloc(p.length); for (let i = 0; i < p.length; i++) m[i] = p[i] ^ mask[i & 3];
  return Buffer.concat([head, mask, m]);
}
const ports = []; let done = 0;
for (let c = 0; c < K; c++) {
  const s = net.connect(port, host, () => {
    const key = crypto.randomBytes(16).toString("base64");
    s.write(`GET /ws/websocket HTTP/1.1\r\nHost: ${host}:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
  });
  let stage = 0, buf = "";
  s.on("data", (d) => {
    buf += d.toString("latin1");
    if (stage === 0 && buf.includes("\r\n\r\n")) { stage = 1; buf = ""; s.write(wsText(frame("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0" }))); }
    else if (stage === 1 && buf.includes("CONNECTED")) {
      stage = 2; let i = 0;
      for (let r = 0; r < REPEAT; r++) for (let id = 2; id <= 203; id++) s.write(wsText(frame("SUBSCRIBE", { id: `s${i++}`, destination: `/topic/stocks/${id}` })));
      ports.push(s.localPort); done++;
      setTimeout(() => s.pause(), 500);      // 구독이 서버에 닿을 시간만 주고 읽기를 멈춘다
      if (done === K) process.stdout.write(JSON.stringify({ ports, subs: 202 * REPEAT }) + "\n");
    }
  });
  s.on("error", (e) => process.stderr.write(`conn ${c}: ${e.message}\n`));
}
setTimeout(() => process.exit(0), HOLD * 1000);
