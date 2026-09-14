#!/usr/bin/env bash
# 카오스 실험 공용 함수 (resilience-plan §6). 각 실험 스크립트가 source 한다.
# 환경변수: API(기본 http://localhost:8080), COMPOSE_ENV(compose 포트 오버라이드, 예: "REDIS_PORT=56379")
API="${API:-http://localhost:8080}"
_HDR=/tmp/chaos_hdr _BODY=/tmp/chaos_body

# probe <라벨> <curl 인자...>  → "  라벨  상태코드  소요ms  [Retry-After=n]"
probe() {
  local name=$1; shift
  local out; out=$(curl -s -o "$_BODY" -D "$_HDR" -w "%{http_code} %{time_total}" "$@")
  local code=${out% *} t=${out#* }
  local retry; retry=$(grep -i "^Retry-After" "$_HDR" | tr -d '\r' | awk '{print $2}')
  printf "  %-36s %s  %6.0fms%s\n" "$name" "$code" "$(echo "$t*1000" | bc)" "${retry:+  Retry-After=$retry}"
}
body() { head -c "${1:-200}" "$_BODY"; echo; }
metrics() { curl -s "$API/actuator/prometheus" | grep "^${1}" | sed 's/^/    /'; }

# 실험용 계정 — signup은 토큰을 바로 돌려준다.
# X-Bench: 가입은 IP당 5회/10분 제한이라 실험을 반복하면 429로 막힌다. local/dev 프로파일은
# app.rate-limit.bench-bypass-enabled=true 라 이 헤더로 우회한다(운영 프로파일에서는 무시된다, P0-4).
fresh_token() {
  curl -s -X POST "$API/api/auth/signup" -H 'Content-Type: application/json' -H 'X-Bench: true' \
    -d "{\"email\":\"chaos-$(date +%s%N)@test.local\",\"password\":\"password123\",\"nickname\":\"chaos\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("accessToken",""))'
}

order_probe() { # order_probe <라벨> <token>
  probe "$1" -X POST "$API/api/brokerage/orders" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $2" -H "X-Idempotency-Key: chaos-$RANDOM-$RANDOM" \
    -d '{"symbol":"005930","side":"BUY","orderType":"MARKET","quantity":1}'
}

steady_state() { # steady_state <token>  — 실험 전/후 공통 프로브 세트
  probe "GET /api/stocks/search"     "$API/api/stocks/search?query=%EC%82%BC%EC%84%B1"
  probe "GET /api/screener (cache)"  "$API/api/screener?tab=realtime"
  probe "POST /api/auth/login (bad pw)" -X POST "$API/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"nobody@test.local","password":"x"}'
  order_probe "POST /api/brokerage/orders" "$1"
  probe "GET readiness"              "$API/actuator/health/readiness"
}

compose() { env ${COMPOSE_ENV:-} docker compose "$@"; }
wait_redis() { for i in $(seq 1 30); do docker exec monticker-redis redis-cli ping 2>/dev/null | grep -q PONG && return 0; sleep 1; done; return 1; }

# recover_probe <token> [max_sec] — 복구 후 주문 경로가 503에서 벗어날 때까지 폴링하고 MTTR을 출력한다.
# Lettuce ConnectionWatchdog의 재연결은 즉시가 아니라 수 초 걸린다 — 단일 프로브로 판정하면 오판한다.
recover_probe() {
  local tok=$1 max=${2:-30} start=$(date +%s) code
  while :; do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/api/brokerage/orders" -H "Content-Type: application/json" \
      -H "Authorization: Bearer $tok" -H "X-Idempotency-Key: rec-$RANDOM-$RANDOM" \
      -d '{"symbol":"005930","side":"BUY","orderType":"MARKET","quantity":1}')
    if [ "$code" != "503" ]; then echo "  주문 경로 복구: HTTP $code, MTTR $(( $(date +%s) - start ))s"; return 0; fi
    [ $(( $(date +%s) - start )) -ge "$max" ] && { echo "  FAIL — ${max}s 내 복구되지 않음 (마지막 $code)"; return 1; }
    sleep 1
  done
}
