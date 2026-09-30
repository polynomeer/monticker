#!/usr/bin/env python3
"""CH-13/CH-14용 토스페이먼츠 스텁 — 결제 API 자리에 세워 장애를 주입한다.

kis-stub.py와 같은 역할이지만 PG 쪽이다. 결제는 브로커와 같은 부류(외부 HTTP, 돈이 움직임,
재시도가 위험)인데도 오래도록 카오스 실험이 하나도 없었다 — ADR-053에서 메운 갭이다.

모드:
  delay   — DELAY_MS 만큼 늦게 200을 돌려준다. "죽지 않고 느린 PG"(CH-14).
  down    — 즉시 연결을 끊는다. "PG 정지"(CH-13).
  error   — 500을 돌려준다. 불확정(INDETERMINATE) 경로.
  timeout — 응답을 아주 오래 붙잡는다. read 타임아웃 → 역시 불확정.

  DELAY_MS=6000 PORT=59444 python3 bench/chaos/toss-pg-stub.py
런타임 변경: POST /_mode/<delay|down|error|timeout>, POST /_delay/<ms>
관측: GET /_stats  → {"charges": n, "lookups": n, "orderIds": [...]}

charges 와 orderIds 가 이 스텁의 본체다. 이중청구는 "같은 orderId로 두 번 청구됐는가"로만
증명할 수 있고, 그건 PG 쪽에서 세어야 보인다.
"""
import http.server, json, os, threading, time

STATE = {"mode": os.environ.get("MODE", "delay"), "delay_ms": int(os.environ.get("DELAY_MS", "6000"))}
STATS = {"charges": 0, "lookups": 0, "orderIds": []}
LOCK = threading.Lock()


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, body, code=200):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _control(self):
        """제어 엔드포인트는 어떤 모드에서도 즉시 응답해야 실험을 되돌릴 수 있다."""
        if self.path.startswith("/_mode/"):
            STATE["mode"] = self.path.rsplit("/", 1)[1]
            self._send(dict(STATE)); return True
        if self.path.startswith("/_delay/"):
            STATE["delay_ms"] = int(self.path.rsplit("/", 1)[1])
            self._send(dict(STATE)); return True
        if self.path == "/_stats":
            with LOCK:
                self._send(dict(STATS)); return True
        if self.path == "/_reset":
            with LOCK:
                STATS.update({"charges": 0, "lookups": 0, "orderIds": []})
            self._send(dict(STATS)); return True
        return False

    def _inject(self):
        """주입된 장애를 적용한다. True를 돌려주면 이미 응답(또는 절단)이 끝났다는 뜻이다."""
        mode = STATE["mode"]
        if mode == "down":
            self.close_connection = True
            try:
                self.connection.close()
            except OSError:
                pass
            return True
        if mode == "error":
            self._send({"code": "PROVIDER_ERROR", "message": "stub 500"}, code=500); return True
        if mode == "timeout":
            time.sleep(60)                      # read 타임아웃(10s)보다 훨씬 길게
            self._send({"code": "TOO_LATE"}, code=500); return True
        time.sleep(STATE["delay_ms"] / 1000)    # delay
        return False

    def _read_body(self):
        """Content-Length 와 chunked 둘 다 받는다 — 청구 바디의 orderId 가 이 실험의 증거다."""
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline(); break
                chunks.append(self.rfile.read(size)); self.rfile.read(2)
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def do_POST(self):
        if self._control():
            return
        raw = self._read_body()

        if "/v1/billing/" in self.path and "authorizations" not in self.path:
            order_id = None
            try:
                order_id = json.loads(raw or b"{}").get("orderId")
            except ValueError:
                pass
            with LOCK:
                STATS["charges"] += 1
                if order_id:
                    STATS["orderIds"].append(order_id)

        if self._inject():
            return

        if self.path.endswith("/authorizations/issue"):
            return self._send({
                "billingKey": "stub_billing_key",
                "customerKey": "stub_customer",
                "card": {"company": "STUB", "number": "1234-56**-****-7890"},
            })
        return self._send({
            "paymentKey": "stub_payment_key",
            "orderId": "stub_order",
            "status": "DONE",
            "totalAmount": 9900,
            "method": "카드",
        })

    def do_GET(self):
        if self._control():
            return
        with LOCK:
            STATS["lookups"] += 1
        if self._inject():
            return

        # /v1/payments/orders/<orderId> 는 "그 orderId로 실제 청구된 적이 있는가"에 답해야 한다.
        # 무조건 DONE을 주면 불확정 복구가 항상 "이미 결제됨"으로 끝나서, 정작 검증하려던
        # "청구된 적 없으면 정상 청구한다" 경로가 한 번도 실행되지 않는다(실제로 겪었다).
        if "/payments/orders/" in self.path:
            order_id = self.path.rsplit("/", 1)[1]
            with LOCK:
                charged = order_id in STATS["orderIds"]
            if not charged:
                return self._send({"code": "NOT_FOUND_PAYMENT", "message": "미등록 orderId"}, code=404)
            return self._send({
                "paymentKey": "stub_payment_key",
                "orderId": order_id,
                "status": "DONE",
                "totalAmount": 9900,
                "method": "카드",
            })

        # /v1/payments/<paymentKey> — 웹훅 재조회 경로. 여기는 그대로 DONE.
        return self._send({
            "paymentKey": "stub_payment_key",
            "orderId": self.path.rsplit("/", 1)[1],
            "status": "DONE",
            "totalAmount": 9900,
            "method": "카드",
        })

    def log_message(self, *a):
        pass


http.server.ThreadingHTTPServer(
    ("127.0.0.1", int(os.environ.get("PORT", "59444"))), H
).serve_forever()
