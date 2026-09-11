#!/usr/bin/env python3
"""CH-06용 KIS 스텁 — 정상 JSON을 돌려주되 DELAY_MS 만큼 늦게 응답한다.

"죽지 않고 느린" 증권사를 흉내낸다. 지연을 slowCallDurationThreshold(3s)와 read 타임아웃(5s) 사이에
두면 호출은 전부 '성공'이라 failureRate는 0이고, slow-call 감지만이 브레이커를 열 수 있다 —
P0-2에서 추가한 설정이 실제로 동작하는지 분리해서 본다.
  DELAY_MS=4000 PORT=59443 python3 bench/chaos/kis-stub.py
런타임 변경: POST /_delay/<ms>
"""
import http.server, json, os, threading, time
DELAY = {"ms": int(os.environ.get("DELAY_MS", "4000"))}
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, body, code=200):
        data = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        if self.path.startswith("/_delay/"):
            DELAY["ms"] = int(self.path.rsplit("/", 1)[1]); return self._send({"delay_ms": DELAY["ms"]})
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        time.sleep(DELAY["ms"] / 1000)
        if self.path == "/oauth2/tokenP":
            return self._send({"access_token": "stub-token", "expires_in": 86400, "token_type": "Bearer"})
        return self._send({"rt_cd": "0", "msg1": "stub", "output": {}})
    def do_GET(self):
        time.sleep(DELAY["ms"] / 1000); return self._send({"rt_cd": "0", "msg1": "stub", "output": {}})
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("PORT", "59443"))), H).serve_forever()
