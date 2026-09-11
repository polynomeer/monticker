#!/usr/bin/env bash
# CH-03 Postgres primary 정지 (resilience-plan §6.2)
#
# 가설: readiness가 DOWN으로 떨어져(P0-6: db 포함) LB에서 제외되고, 요청은 매달리지 않고 빠르게
#   실패하며(Hikari connection-timeout 3s), DB 복구 후 2분 내 수동 개입 없이 readiness UP·정상화된다.
#   liveness는 내내 UP이어야 한다 — DB 장애가 pod 재시작 루프가 되면 안 된다.
# 중단: liveness DOWN 또는 복구 후 2분 내 미정상화.
set -u
source "$(dirname "$0")/lib.sh"
TOK=$(fresh_token)
health() { printf "  readiness=%s liveness=%s\n" "$(curl -s -o /dev/null -w '%{http_code}' $API/actuator/health/readiness)" "$(curl -s -o /dev/null -w '%{http_code}' $API/actuator/health/liveness)"; }

echo "== 1. 정상"; health; probe "GET /api/stocks/1" "$API/api/stocks/1"
echo "== 2. 주입: docker compose stop postgres"; compose stop postgres >/dev/null; sleep 3
echo "== 3. 관측 (DB 없음)"
probe "GET /api/stocks/search (DB)"  "$API/api/stocks/search?query=%EC%82%BC%EC%84%B1"
probe "GET /api/screener (DB)"       "$API/api/screener?tab=realtime"
order_probe "POST /api/brokerage/orders" "$TOK"
for i in 1 2 3 4 5 6; do health; sleep 5; done   # readiness가 DOWN으로 전환되는 시점
echo "== 4. 복구: docker compose start postgres"; compose start postgres >/dev/null
for i in $(seq 1 30); do docker exec monticker-postgres pg_isready -U monticker >/dev/null 2>&1 && break; sleep 1; done
echo "== 5. 복구 후 — readiness UP까지 폴링 (최대 120s)"; start=$(date +%s)
while :; do c=$(curl -s -o /dev/null -w '%{http_code}' $API/actuator/health/readiness); [ "$c" = "200" ] && { echo "  readiness UP, MTTR $(( $(date +%s) - start ))s"; break; }; [ $(( $(date +%s) - start )) -ge 120 ] && { echo "  FAIL — 120s 내 readiness 미복구"; break; }; sleep 2; done
probe "GET /api/stocks/1" "$API/api/stocks/1"; order_probe "POST /api/brokerage/orders" "$TOK"; health
