#!/usr/bin/env bash
# CH-07 API pod 강제 종료 (SIGKILL) (resilience-plan §6.2) — 주문·원장 정합성
#
# 가설: 주문 처리 도중 프로세스가 죽어도 (1) 커밋된 주문·체결·잔고는 남고 미커밋은 통째로 사라지며
#   (2) 커밋 후 아직 안 돈 리스너(원장 기록, Kafka 외부화)는 event_publication에 미완료로 남았다가
#   Outbox가 재전송하고 (3) 미완료 Saga는 recoverIncomplete가 정리해, 최종적으로 ADR-043 대사가
#   전 유저 drift 0을 낸다. 잔고 정합성 100%가 성공 기준이다.
# 주입: 주문 루프 중 kill -9. 로컬은 java -jar로 띄운 PID(API_PID 또는 pgrep api-0.0.1-SNAPSHOT.jar).
# 재기동: RESTART_CMD 환경변수 (기본: 같은 jar를 같은 환경으로 nohup 재실행).
# 중단: 재기동 후 readiness 120s 내 미복구, 또는 6분 뒤에도 mismatch > 0.
set -u
source "$(dirname "$0")/lib.sh"
PSQL="docker exec monticker-postgres psql -U monticker -d monticker -tA -c"
USERS=${USERS:-12}
JAR=${JAR:-backend/api/build/libs/api-0.0.1-SNAPSHOT.jar}
LOG=${LOG:-/tmp/ch07-api.log}
STOCK=$($PSQL "SELECT stock_id FROM candles_1m ORDER BY candle_time DESC LIMIT 1")

pid() { [ -n "${API_PID:-}" ] && echo "$API_PID" || pgrep -f 'api-0.0.1-SNAPSHOT.jar' | head -1; }
readiness() { curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health/readiness"; }
wait_ready() { local s=$(date +%s); until [ "$(readiness)" = "200" ] || [ $(( $(date +%s) - s )) -ge ${1:-120} ]; do sleep 2; done; echo $(( $(date +%s) - s )); }
incomplete_pubs() { $PSQL "SELECT count(*) FROM event_publication WHERE completion_date IS NULL"; }
incomplete_sagas() { $PSQL "SELECT count(*) FROM order_sagas WHERE status IN ('STARTED','COMPENSATING')"; }
reconcile() { # 전 활성 유저 대사 → "checked mismatched"
  curl -s -X POST "$API/api/admin/batch/ledger-reconciliation" -H "Authorization: Bearer $ADMIN" >/dev/null
  $PSQL "SELECT count(*) || ' ' || count(*) FILTER (WHERE mismatch) FROM ledger_snapshots WHERE as_of_date = current_date AND user_id = ANY(ARRAY[$UIDS])"
}

echo "== 0. 실험 유저 $USERS명 생성"
TOKS=(); for i in $(seq 1 $USERS); do TOKS+=("$(fresh_token)"); done
UIDS=$($PSQL "SELECT string_agg(id::text, ',') FROM (SELECT id FROM users ORDER BY id DESC LIMIT $USERS) t")
$PSQL "UPDATE users SET role='ADMIN' WHERE id = (SELECT max(id) FROM users)" >/dev/null
ADMIN_EMAIL=$($PSQL "SELECT email FROM users ORDER BY id DESC LIMIT 1")
ADMIN=""; for t in 1 2 3 4 5; do ADMIN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' -H 'X-Bench: true' -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"password123\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("accessToken",""))'); [ -n "$ADMIN" ] && break; sleep 2; done
[ -z "$ADMIN" ] && { echo "  FAIL — admin 토큰 없음"; exit 1; }
echo "  users=$UIDS pid=$(pid)"

echo "== 1. 주문 루프 시작 (유저별 BUY→SELL 교대, 성공 시에만 방향 전환, 백그라운드)"
# @RateLimited(30/60s)가 유저별이라 유저당 2.5초 간격 — $USERS명이면 초당 $USERS/2.5건
OK=/tmp/ch07-ok; : > $OK; LOOPS=()
for i in $(seq 0 $((USERS-1))); do
  ( side=BUY; while :; do
      c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST "$API/api/matching/orders" -H 'Content-Type: application/json' \
          -H "Authorization: Bearer ${TOKS[$i]}" -H "X-Idempotency-Key: ch07-$i-$RANDOM-$RANDOM" \
          -d "{\"stockId\":$STOCK,\"side\":\"$side\",\"orderType\":\"MARKET\",\"quantity\":1}")
      echo "$side $c" >> $OK; [ "$c" = 200 ] && { [ "$side" = BUY ] && side=SELL || side=BUY; }; sleep 2.5; done ) 2>/dev/null &
  LOOPS+=($!); disown
done
sleep ${WARMUP:-10}
echo "== 2. 주입: kill -9 $(pid) (루프 계속 → 죽어 있는 동안의 실패도 센다)"; P=$(pid); kill -9 "$P"; T_KILL=$(date +%s)
sleep 3; echo "  죽은 뒤 readiness=$(readiness)"
echo "== 3. 재기동"
if [ -n "${RESTART_CMD:-}" ]; then bash -c "$RESTART_CMD"; else (nohup java -jar "$JAR" > "$LOG" 2>&1 &); fi
echo "  readiness UP까지 $(wait_ready 180)s (SIGKILL 시점부터 $(( $(date +%s) - T_KILL ))s)"
sleep 3; for l in "${LOOPS[@]}"; do pkill -P "$l" 2>/dev/null; kill "$l" 2>/dev/null; done; sleep 1   # 주문 루프 종료
echo "  주문 결과(side code×n): $(sort $OK | uniq -c | awk '{printf "%s/%s×%s ", $2, $3, $1}')"
echo "== 4. 즉시 점검 (Outbox 재전송·Saga 복구 전)"
echo "  event_publication 미완료=$(incomplete_pubs)  saga 미완료=$(incomplete_sagas)"
echo "  대사(checked mismatched)=$(reconcile)"
echo "== 5. 자동 복구 대기 — Outbox 재전송 1분 유예 + 5분 주기, Saga recoverIncomplete 5분 (최대 7분)"
start=$(date +%s)
while :; do p=$(incomplete_pubs); s=$(incomplete_sagas); el=$(( $(date +%s) - start ))
  echo "  +${el}s 미완료 pubs=$p sagas=$s"
  [ "$p" = "0" ] && [ "$s" = "0" ] && break
  [ $el -ge 420 ] && { echo "  7분 내 미수렴"; break; }; sleep 30; done
echo "== 6. 최종 대사"
R=$(reconcile); echo "  대사(checked mismatched)=$R"
metrics ledger_reconciliation_mismatch_total; metrics saga_incomplete_total
[ "${R#* }" = "0" ] && [ "$(incomplete_pubs)" = "0" ] && echo "  PASS — 잔고 정합성 100%, 미완료 0" || echo "  FAIL"
