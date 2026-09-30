#!/usr/bin/env bash
# CH-14 결제 PG 지연·불확정 (resilience-plan §6.2) — ADR-053의 완료 판정.
#
# CH-06(브로커 지연)의 결제판이지만 묻는 것이 하나 더 있다. 브로커는 "느린 외부가 스레드를
# 먹는가"만 보면 되지만, 결제는 **응답을 못 받았을 때 돈이 어떻게 됐는지 모른다**는 문제가
# 따로 있다. 그 불확정 상태를 어떻게 처리하느냐가 이중청구와 미청구를 가른다.
#
# 가설:
#   1) 6초 응답(slowCallDurationThreshold 5s 초과, read 타임아웃 10s 미만) = 전부 '성공'인데도
#      slow-call 감지만으로 tossPg 브레이커가 OPEN 된다 — failureRate 는 0이다
#   2) OPEN 후 호출은 즉시 거절된다(수 ms). PG를 기다리는 스레드가 쌓이지 않는다
#   3) read 타임아웃(timeout 모드)은 실패가 아니라 **불확정**으로 다뤄진다 —
#      갱신은 재청구하지 않고 orderId로 PG에 되묻는다
#   4) **같은 orderId 로 두 번 청구되지 않는다** — 스텁의 charges/orderIds 로 직접 센다
#
# 전제: CH-13과 동일.
# 실행: API=http://localhost:58080 STUB=http://localhost:59444 bench/chaos/ch14-pg-latency.sh
set -u
source "$(dirname "$0")/lib.sh"
STUB="${STUB:-http://localhost:59444}"

cb() { curl -s "$API/actuator/prometheus" \
  | grep -E "circuitbreaker_(state\{.*name=\"tossPg\".*\} 1\.0|slow_call_rate\{.*name=\"tossPg\"|failure_rate\{.*name=\"tossPg\")" \
  | sed -E 's/.*state="([a-z_]+)".*/    state=\1/; s/.*slow_call_rate.*\} /    slow_call_rate=/; s/.*failure_rate.*\} /    failure_rate=/'; }

register() {
  local tok; tok=$(fresh_token)
  probe "$1" -X POST "$API/api/subscription/billing/register" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $tok" -d '{"authKey":"stub_auth","customerKey":"stub_customer"}'
}

stats() { curl -s "$STUB/_stats"; }

dup_check() {
  # 이중청구는 "같은 orderId 가 두 번 이상 왔는가"로만 증명된다.
  stats | python3 -c '
import sys, json, collections
s = json.load(sys.stdin)
ids = s["orderIds"]
dups = {k: v for k, v in collections.Counter(ids).items() if v > 1}
print("    charges=%d lookups=%d 고유orderId=%d" % (s["charges"], s["lookups"], len(set(ids))))
print("    중복 청구: %s건%s" % (dups if dups else 0, "  <- FAIL" if dups else "  <- PASS"))'
}

echo "== 0. 스텁 초기화"
curl -s -X POST "$STUB/_mode/delay" >/dev/null; curl -s -X POST "$STUB/_delay/0" >/dev/null
curl -s -X POST "$STUB/_reset"      >/dev/null

echo "== 1. 정상 (스텁 지연 0ms)"
register "POST /api/subscription/billing/register"
probe "GET /api/screener" "$API/api/screener?tab=realtime"
cb

echo "== 1.5 갱신 대상 구독을 하나 만든다 (PG 정상일 때)"
clear_renewal_backlog
RENEW_EMAIL=$(seed_renewal_candidate)
echo "  대상: $RENEW_EMAIL"; renewal_state "$RENEW_EMAIL"

echo "== 2. 주입: PG 응답 6000ms (성공하지만 느림)"
curl -s -X POST "$STUB/_delay/6000" >/dev/null

echo "== 3. 관측 — 6번 호출(슬라이딩 윈도우) 뒤 OPEN 되어야 한다. failure_rate 는 0으로 남는다"
for i in 1 2 3 4 5 6 7 8; do register "register #$i"; done
cb

echo "  PG 지연 중 무관한 API:"
probe "GET /api/screener"      "$API/api/screener?tab=realtime"
probe "GET /api/stocks/search" "$API/api/stocks/search?query=%EC%82%BC%EC%84%B1"

echo "  동시 10건 (열린 브레이커면 즉시 거절):"
start=$(date +%s.%N)
for i in $(seq 1 10); do
  ( tok=$(fresh_token); curl -s -o /dev/null -X POST "$API/api/subscription/billing/register" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $tok" \
      -d '{"authKey":"stub_auth","customerKey":"stub_customer"}' ) &
done; wait
echo "    10건 완료 $(echo "($(date +%s.%N) - $start)*1000" | bc | cut -d. -f1)ms"
echo "    (브레이커가 없었다면 10 × 6s. 열려 있으면 거의 0이다)"

echo "== 4. 불확정 주입: 응답을 아예 안 준다 (read 타임아웃 10s)"
curl -s -X POST "$STUB/_mode/timeout" >/dev/null
# waitDurationInOpenState(60s)가 지나야 다음 호출이 HALF_OPEN 프로브로 통과한다.
# resilience4j는 타이머가 아니라 "다음 호출 시점"에 전이하므로, 상태를 폴링해봐야 OPEN 그대로다
# (실제로 폴링 방식으로 짰다가 180s를 헛돌았다). 기다린 뒤 호출로 깨우는 게 맞다.
echo "  브레이커가 프로브를 허용할 때까지 61s 대기"
sleep 61
register "register (타임아웃 경로)"
cb

echo "== 5. 갱신 배치를 불확정(타임아웃) 상태에서 반복 실행한다 (3회)"
echo "  브레이커가 프로브를 허용할 때까지 61s 대기 — 배치의 첫 호출이 실제로 PG까지 간다"
sleep 61
ADM=$(admin_token)
for i in 1 2 3; do
  probe "POST /api/admin/batch/subscription-renewal #$i" -X POST \
    "$API/api/admin/batch/subscription-renewal" -H "Authorization: Bearer $ADM"
done
renewal_state "$RENEW_EMAIL"
dup_check
echo "  기대: 결제 기록은 PENDING 유지(FAILED로 굳히지 않는다), 같은 orderId 중복 청구 0"
echo "        — 응답을 못 받았으니 청구 여부를 모른다. 여기서 실패로 단정하면 다음 실행이 재청구다."

echo "== 6. 복구 — PG 정상화 후 같은 주기를 마저 처리한다"
curl -s -X POST "$STUB/_mode/delay" >/dev/null; curl -s -X POST "$STUB/_delay/0" >/dev/null
sleep 65; cb
probe "POST /api/admin/batch/subscription-renewal" -X POST \
  "$API/api/admin/batch/subscription-renewal" -H "Authorization: Bearer $ADM"
renewal_state "$RENEW_EMAIL"
cb; dup_check
echo "  기대: PENDING → SUCCESS, orderId 는 장애 전과 동일, 총 청구 1회"
