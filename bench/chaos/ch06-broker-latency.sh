#!/usr/bin/env bash
# CH-06 브로커(KIS) 지연 (resilience-plan §6.2) — 실제 돈이 걸린 경로. P0-2의 완료 판정.
#
# 가설: KIS가 "죽지 않고 느려지면"(4초 응답 = slowCallDurationThreshold 3s 초과, read 타임아웃 5s 미만)
#   1) 호출은 성공하지만 느리다 → failureRate 0% 인데도 slow-call 감지로 브레이커가 OPEN 된다
#   2) OPEN 후 호출은 즉시 거절된다(수 ms) — 스레드가 KIS를 기다리며 쌓이지 않는다
#   3) KIS 지연 중에도 무관한 API(스크리너)의 지연이 영향받지 않는다
#   4) KIS가 정상으로 돌아오면 waitDurationInOpenState(30s) 뒤 HALF_OPEN → CLOSED로 자동 복구
# 전제: bench/chaos/kis-stub.py 가 KIS 자리에 있고 api가 BROKERAGE_MOCK_ENABLED=false KIS_BASE_URL=<stub>.
# 실행: API=http://localhost:58080 STUB=http://localhost:59443 bench/chaos/ch06-broker-latency.sh
set -u
source "$(dirname "$0")/lib.sh"
STUB="${STUB:-http://localhost:59443}"
cb() { curl -s "$API/actuator/prometheus" | grep -E "circuitbreaker_(state\{.*name=\"kis\".*\} 1\.0|slow_call_rate\{.*name=\"kis\")" | sed -E 's/.*state="([a-z_]+)".*/    state=\1/; s/.*slow_call_rate.*\} /    slow_call_rate=/'; }
# /connect 는 userId당 10회/시간 제한 — 호출마다 새 계정을 쓴다
connect() { local tok; tok=$(fresh_token); probe "$1" -X POST "$API/api/brokerage/connect" -H "Content-Type: application/json" -H "Authorization: Bearer $tok" -d '{"provider":"KIS","appKey":"k","appSecret":"s","accountNumber":"12345678-01"}'; }

echo "== 1. 정상 (스텁 지연 0ms)"; curl -s -X POST "$STUB/_delay/0" >/dev/null
connect "POST /api/brokerage/connect"; probe "GET /api/screener" "$API/api/screener?tab=realtime"; cb
echo "== 2. 주입: KIS 응답 4000ms (성공하지만 느림)"; curl -s -X POST "$STUB/_delay/4000" >/dev/null
echo "== 3. 관측 — 6번 호출(슬라이딩 윈도우) 뒤 OPEN 되어야 한다"
for i in 1 2 3 4 5 6 7 8; do connect "connect #$i"; done; cb
echo "  KIS 지연 중 무관한 API:"; probe "GET /api/screener" "$API/api/screener?tab=realtime"
echo "  동시 10건 connect (스레드 고갈 여부 — 열린 브레이커면 즉시 거절):"; start=$(date +%s.%N)
for i in $(seq 1 10); do ( tok=$(fresh_token); curl -s -o /dev/null -X POST "$API/api/brokerage/connect" -H "Content-Type: application/json" -H "Authorization: Bearer $tok" -d '{"provider":"KIS","appKey":"k","appSecret":"s","accountNumber":"12345678-01"}' ) & done; wait
echo "    10건 완료 $(echo "($(date +%s.%N) - $start)*1000" | bc | cut -d. -f1)ms"
echo "== 4. 복구: 스텁 지연 0ms"; curl -s -X POST "$STUB/_delay/0" >/dev/null
echo "== 5. 복구 후 — OPEN 대기(30s) 경과 뒤 HALF_OPEN → CLOSED"; sleep 31; cb
connect "connect (half-open probe 1)"; connect "connect (half-open probe 2)"; cb
