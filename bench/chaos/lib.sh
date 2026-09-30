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

# ADMIN 권한이 필요한 경로(배치 수동 실행 등)용 토큰. 가입 후 DB에서 역할만 올리고
# 다시 로그인한다 — 토큰의 role 클레임은 발급 시점에 박히므로 재로그인이 필요하다.
admin_token() {
  local email="chaos-admin-$(date +%s%N)@test.local"
  curl -s -X POST "$API/api/auth/signup" -H 'Content-Type: application/json' -H 'X-Bench: true' \
    -d "{\"email\":\"$email\",\"password\":\"password123\",\"nickname\":\"chaosadm\"}" >/dev/null
  docker exec monticker-postgres psql -qtAU monticker -d monticker \
    -c "UPDATE users SET role='ADMIN' WHERE email='$email'" >/dev/null 2>&1
  curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' -H 'X-Bench: true' \
    -d "{\"email\":\"$email\",\"password\":\"password123\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("accessToken",""))'
}

# 갱신 배치가 집어갈 구독을 하나 만든다 (CH-13/CH-14).
# PG가 건강한 동안 빌링키 등록 + 구독까지 API로 끝내고, 만료만 SQL로 당긴다 —
# 빌링키는 EncryptedStringConverter로 암호화 저장되므로 SQL로 직접 넣으면 복호화에 실패한다.
# 에코: 만들어진 계정 email.
seed_renewal_candidate() {
  local email="chaos-renew-$(date +%s%N)@test.local" tok
  tok=$(curl -s -X POST "$API/api/auth/signup" -H 'Content-Type: application/json' -H 'X-Bench: true' \
    -d "{\"email\":\"$email\",\"password\":\"password123\",\"nickname\":\"renew\"}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin).get("accessToken",""))')
  curl -s -o /dev/null -X POST "$API/api/subscription/billing/register" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $tok" -d '{"authKey":"stub_auth","customerKey":"stub_customer"}'
  # /subscribe 가 아니라 confirm 플로우를 쓴다 — TossPgClient.requestPayment 는 "웹훅을 쓰라"는
  # 스텁이라 항상 실패한다(SubscriptionService 주석 참고). 실제 유료 구독이 만들어지는 경로는
  # 프론트 SDK 결제 → POST /payment/confirm 이므로 여기서도 그 경로를 그대로 탄다.
  curl -s -o /dev/null -X POST "$API/api/subscription/payment/confirm" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $tok" \
    -d '{"paymentKey":"stub_payment_key","orderId":"seed_order","amount":9900,"planCode":"PRO"}'
  # 갱신 대상 조건: expires_at < now + 1day (SubscriptionRenewalJobConfig.expiringSubscriptionReader)
  docker exec monticker-postgres psql -qtAU monticker -d monticker -c \
    "UPDATE user_subscriptions SET expires_at = NOW() + INTERVAL '1 hour'
      WHERE user_id = (SELECT id FROM users WHERE email='$email')" >/dev/null 2>&1
  echo "$email"
}

# 이전 실험이 남긴 갱신 대상을 창 밖으로 밀어낸다. 지우지 않고 만료만 미룬다 —
# 실험마다 대상이 하나씩 쌓이면 배치가 매번 예전 잔여분부터 처리해 관측이 흐려진다.
clear_renewal_backlog() {
  docker exec monticker-postgres psql -qtAU monticker -d monticker -c "
    UPDATE user_subscriptions SET expires_at = NOW() + INTERVAL '365 days'
     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'chaos-renew-%')" >/dev/null 2>&1
}

# 갱신 후 상태 출력 — 강등됐는지, 결제 기록이 어떤 상태로 남았는지.
renewal_state() { # renewal_state <email>
  docker exec monticker-postgres psql -qAU monticker -d monticker -c "
    SELECT p.code AS plan, s.status,
           (SELECT string_agg(pr.status || ':' || COALESCE(pr.pg_order_id,'-'), ', ')
              FROM payment_records pr WHERE pr.user_id = u.id) AS payments
      FROM users u JOIN user_subscriptions s ON s.user_id = u.id
      JOIN subscription_plans p ON p.id = s.plan_id
     WHERE u.email = '$1'" 2>/dev/null | sed 's/^/    /'
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
