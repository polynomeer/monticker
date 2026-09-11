#!/usr/bin/env bash
# CH-02 Redis 지연 주입 (resilience-plan §6.2)
#
# 가설: spring.data.redis.timeout(200ms)이 발동해 요청이 Redis 지연만큼 매달리지 않는다.
#       Tomcat 스레드가 고갈되지 않아 동시 요청이 지연 시간 안에 전부 끝난다.
# 전제: toxiproxy가 Redis 앞에 있고 API가 toxiproxy 포트를 바라본다.
#   docker run -d --name toxiproxy -p 8474:8474 -p 6380:6380 ghcr.io/shopify/toxiproxy:2.9.0
#   curl -X POST localhost:8474/proxies -d '{"name":"redis","listen":"0.0.0.0:6380","upstream":"host.docker.internal:6379"}'
#   API를 REDIS_PORT=6380 으로 기동
# 실행: API=http://localhost:8080 TOXI=http://localhost:8474 LATENCY_MS=2000 bench/chaos/ch02-redis-latency.sh
set -u
source "$(dirname "$0")/lib.sh"
TOXI="${TOXI:-http://localhost:8474}"; LATENCY_MS="${LATENCY_MS:-2000}"
TOK=$(fresh_token); [ -n "$TOK" ] || { echo "signup 실패"; exit 1; }

echo "== 1. 정상";                       steady_state "$TOK"
echo "== 2. 주입: Redis 응답 ${LATENCY_MS}ms 지연"
curl -s -X POST "$TOXI/proxies/redis/toxics" -H 'Content-Type: application/json' \
  -d "{\"name\":\"lat\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$LATENCY_MS,\"jitter\":0}}" >/dev/null
echo "== 3. 관측 — 각 요청이 ${LATENCY_MS}ms씩 매달리면 실패"; steady_state "$TOK"
echo "  동시 20요청:"; start=$(date +%s.%N); for i in $(seq 1 20); do curl -s -o /dev/null "$API/api/screener?tab=realtime" & done; wait
echo "    20건 완료 $(echo "($(date +%s.%N) - $start)*1000" | bc | cut -d. -f1)ms (스레드 고갈이면 ${LATENCY_MS}ms × 여러 배)"
echo "  metrics:"; metrics redis_command_failed_total
echo "== 4. 복구: 지연 제거"; curl -s -X DELETE "$TOXI/proxies/redis/toxics/lat" >/dev/null
echo "== 5. 복구 후";                    recover_probe "$TOK" 30; steady_state "$TOK"
