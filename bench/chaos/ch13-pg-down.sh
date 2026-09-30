#!/usr/bin/env bash
# CH-13 결제 PG 정지 (resilience-plan §6.2) — 돈이 걸린 두 번째 경로.
#
# 가설:
#   1) PG가 죽어도 결제와 무관한 API는 영향을 받지 않는다(격리)
#   2) 빌링키 등록은 명확한 실패로 반환된다 — 매달리지 않는다
#   3) 서킷브레이커 tossPg가 열려 이후 호출이 즉시 거절된다
#   4) **정기결제 갱신은 PG 장애를 "카드 거절"로 읽지 않는다** — 다운그레이드가 일어나면 안 된다
#      (ADR-053. 이게 이 실험의 본체다. 나머지는 브로커 쪽에서 이미 검증된 패턴이다)
#   5) PG가 돌아오면 브레이커가 자동으로 닫힌다
#
# 전제: bench/chaos/toss-pg-stub.py 가 PG 자리에 있고,
#       api가 PG_MOCK_ENABLED=false TOSS_SECRET_KEY=stub TOSS_PG_BASE_URL=<stub>.
# 실행: API=http://localhost:58080 STUB=http://localhost:59444 bench/chaos/ch13-pg-down.sh
set -u
source "$(dirname "$0")/lib.sh"
STUB="${STUB:-http://localhost:59444}"

cb() { curl -s "$API/actuator/prometheus" \
  | grep -E "circuitbreaker_(state\{.*name=\"tossPg\".*\} 1\.0|calls_seconds_count\{.*name=\"tossPg\")" \
  | sed -E 's/.*state="([a-z_]+)".*/    state=\1/; s/.*kind="([a-z_]+)".*\} /    \1=/'; }

# /register 는 userId당 10회/시간 제한 — 호출마다 새 계정을 쓴다.
register() {
  local tok; tok=$(fresh_token)
  probe "$1" -X POST "$API/api/subscription/billing/register" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $tok" -d '{"authKey":"stub_auth","customerKey":"stub_customer"}'
}

stats() { echo "  스텁 집계: $(curl -s "$STUB/_stats")"; }

echo "== 0. 스텁 초기화 (정상 모드)"
curl -s -X POST "$STUB/_mode/delay" >/dev/null; curl -s -X POST "$STUB/_delay/0" >/dev/null
curl -s -X POST "$STUB/_reset" >/dev/null

echo "== 1. 정상 상태"
register "POST /api/subscription/billing/register"
probe "GET /api/screener (무관한 경로)" "$API/api/screener?tab=realtime"
probe "GET /api/subscription/plans"     "$API/api/subscription/plans"
cb; stats

echo "== 1.5 갱신 대상 구독을 하나 만든다 (PG 정상일 때)"
clear_renewal_backlog
RENEW_EMAIL=$(seed_renewal_candidate)
echo "  대상: $RENEW_EMAIL"; renewal_state "$RENEW_EMAIL"

echo "== 2. 주입: PG 정지 (연결 즉시 절단)"
curl -s -X POST "$STUB/_mode/down" >/dev/null

echo "== 3. 관측 — 등록은 실패하되 매달리지 않아야 한다"
for i in 1 2 3 4 5 6 7 8; do register "register #$i"; done
cb

echo "  PG 장애 중 무관한 API:"
probe "GET /api/screener"           "$API/api/screener?tab=realtime"
probe "GET /api/stocks/search"      "$API/api/stocks/search?query=%EC%82%BC%EC%84%B1"
probe "GET readiness"               "$API/actuator/health/readiness"

echo "  동시 10건 등록 (스레드 고갈 여부 — 열린 브레이커면 즉시 거절):"
start=$(date +%s.%N)
for i in $(seq 1 10); do
  ( tok=$(fresh_token); curl -s -o /dev/null -X POST "$API/api/subscription/billing/register" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $tok" \
      -d '{"authKey":"stub_auth","customerKey":"stub_customer"}' ) &
done; wait
echo "    10건 완료 $(echo "($(date +%s.%N) - $start)*1000" | bc | cut -d. -f1)ms"
stats

echo "== 4. 갱신 배치를 PG 장애 중에 돌린다 — 다운그레이드가 없어야 한다"
ADM=$(admin_token)
for i in 1 2 3; do
  probe "POST /api/admin/batch/subscription-renewal #$i" -X POST \
    "$API/api/admin/batch/subscription-renewal" -H "Authorization: Bearer $ADM"
done
echo "  대상 구독 상태 (강등되지 않아야 한다):"; renewal_state "$RENEW_EMAIL"
echo "  결제 기록 상태 / 장애 중 강등 건수:"
docker exec monticker-postgres psql -qAU monticker -d monticker -c "
  SELECT status, COUNT(*) FROM payment_records
   WHERE created_at > NOW() - INTERVAL '10 minutes' GROUP BY status;
  SELECT COUNT(*) AS downgraded_during_outage
    FROM user_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
   WHERE p.code = 'FREE' AND s.updated_at > NOW() - INTERVAL '10 minutes';" 2>/dev/null | sed 's/^/    /'
echo "  기대: PENDING 은 늘어도 FAILED 증가 0, downgraded_during_outage = 0"

echo "== 5. 복구: PG 정상화"
curl -s -X POST "$STUB/_mode/delay" >/dev/null; curl -s -X POST "$STUB/_delay/0" >/dev/null
echo "== 6. 복구 후 — OPEN 대기(60s) 경과 뒤 HALF_OPEN → CLOSED"
sleep 61; cb
register "register (half-open probe)"
cb; stats
echo "== 7. PG 복구 후 갱신 — 같은 주기가 정상 결제되어야 한다 (재청구는 1회뿐)"
probe "POST /api/admin/batch/subscription-renewal" -X POST \
  "$API/api/admin/batch/subscription-renewal" -H "Authorization: Bearer $ADM"
renewal_state "$RENEW_EMAIL"; stats
