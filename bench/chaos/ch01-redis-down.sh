#!/usr/bin/env bash
# CH-01 Redis 전면 정지 (resilience-plan §6.2)
#
# 가설: 조회·로그인은 계속 동작하고(레이트리밋·캐시 fail-open), 주문만 명시적 503+Retry-After(fail-closed).
#       readiness는 UP을 유지한다(Redis는 readiness 대상이 아니다). 복구 후 수동 개입 없이 정상화.
# 중단: 조회 5xx가 나오면 즉시 중단.
# 실행: API=http://localhost:8080 COMPOSE_ENV="REDIS_PORT=6379" bench/chaos/ch01-redis-down.sh
set -u
source "$(dirname "$0")/lib.sh"
TOK=$(fresh_token); [ -n "$TOK" ] || { echo "signup 실패 — API가 떠 있는지 확인"; exit 1; }

echo "== 1. 정상 상태";                 steady_state "$TOK"
echo "== 2. 주입: docker compose stop redis"; compose stop redis >/dev/null; sleep 2
echo "== 3. 관측 (Redis 없음)";          steady_state "$TOK"
order_probe "POST /api/brokerage/orders (재확인)" "$TOK"; echo "    503 body: $(body 160)"
probe "POST /api/auth/signup (no redis)" -X POST "$API/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"email\":\"nr-$(date +%s%N)@test.local\",\"password\":\"password123\",\"nickname\":\"nr\"}"
echo "  metrics:"; metrics redis_command_failed_total
echo "== 4. 복구: docker compose start redis"; compose start redis >/dev/null; wait_redis
echo "== 5. 복구 후 (Lettuce 재연결 대기 — MTTR 측정)"; recover_probe "$TOK" 30
steady_state "$TOK"
