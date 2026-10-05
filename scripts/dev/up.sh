#!/usr/bin/env bash
# 로컬 개발 스택 기동: Docker 인프라 → API → Worker → Web 순서로 띄우고 각각 헬스체크를 기다린다.
# 사용법은 --help. 끄기는 Ctrl-C 또는 다른 터미널에서 scripts/dev/down.sh.
set -e
. "$(dirname "$0")/lib.sh"

usage() {
  cat <<EOF
Usage: scripts/dev/up.sh [options]

Options:
  (없음)       기본 모드 — API + Worker(MockPriceGenerator) + Web
               Kafka가 없어서 Kafka를 거치는 기능(검색 색인 ADR-042, 사용자 알림 ADR-065 등)은 동작하지 않는다
  --kafka      Kafka + Go market-gateway 추가. 시세가 Go → Kafka → Worker 경로로 흐른다
  --msa        --kafka + 워커를 컨테이너 3개(market/event/alert 역할)로 분리. 호스트 워커는 띄우지 않는다
  --pinpoint   Pinpoint APM 포함 (HBase 초기화 2~3분)
  -h, --help   이 도움말
EOF
}

WITH_PINPOINT=false
WITH_KAFKA=false
WITH_MSA=false
for arg in "$@"; do
  case "$arg" in
    --pinpoint) WITH_PINPOINT=true ;;
    --kafka)    WITH_KAFKA=true ;;
    --msa)      WITH_MSA=true; WITH_KAFKA=true ;;
    -h|--help)  usage; exit 0 ;;
    *)          echo "알 수 없는 옵션: $arg" >&2; usage >&2; exit 2 ;;
  esac
done
MODE=default
[ "$WITH_KAFKA" = true ] && MODE=kafka
[ "$WITH_MSA" = true ] && MODE=msa

API_PID=""; WORKER_PID=""; WEB_PID=""

# 앱 프로세스만 정리한다. bootRun JVM은 Gradle 데몬이 띄워 gradlew(아래 PID)를 죽여도 남으므로 경로 패턴으로도 죽인다.
stop_apps() {
  kill $API_PID $WORKER_PID $WEB_PID 2>/dev/null || true
  for p in "${APP_JVM_PATTERNS[@]}"; do pkill -f "$p" 2>/dev/null || true; done
  local pid
  for port in "$API_PORT" "$WORKER_PORT" "$WEB_PORT"; do
    [ -n "$port" ] && pid=$(repo_listener_pid "$port") && kill "$pid" 2>/dev/null || true
  done
}

die() {
  local msg="$1" logfile="$2"
  echo ""
  error "$msg"
  if [ -n "$logfile" ] && [ -f "$logfile" ]; then
    echo -e "${YELLOW}──── 마지막 30줄 ($logfile) ────${NC}"
    tail -30 "$logfile"
    echo -e "${YELLOW}────────────────────────────────${NC}"
    echo "전체 로그: $logfile"
  fi
  stop_apps
  echo "인프라 컨테이너는 그대로 둔다 — 정리하려면 scripts/dev/down.sh"
  exit 1
}

cleanup() {
  echo ""
  echo "Stopping..."
  stop_apps
  compose_all stop 2>/dev/null || true
  rm -f "$STATE_FILE"
  wait 2>/dev/null || true
  echo "Done."
  exit 0
}
trap cleanup INT TERM

# ── 포트 결정 ─────────────────────────────────────────────────
# 선호 포트가 비어 있으면 그대로 쓴다. 이 저장소의 프로세스(이전 실행의 잔여물)가 쥐고 있으면 정리하고 재사용한다.
# 그 밖의 프로세스는 절대 죽이지 않고 다음 빈 포트로 우회한다.
#
# 결과는 $RESOLVED_PORT 전역 변수로 받는다 — $(resolve_port ...)처럼 서브셸에서 부르면 CLAIMED_PORTS 갱신이 사라져
# 아직 아무도 리슨하지 않는 포트를 다음 서비스가 또 고른다. 반드시 `resolve_port N; VAR=$RESOLVED_PORT` 형태로.
CLAIMED_PORTS=""
RESOLVED_PORT=""
port_claimed() { case " $CLAIMED_PORTS " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
port_busy() { lsof -ti tcp:"$1" -sTCP:LISTEN > /dev/null 2>&1; }

# docker compose가 이미 띄운 이 프로젝트 컨테이너의 포트는 "점유"가 아니라 "재사용"이다 — 그대로 써야 재기동 없이 붙는다.
compose_published_port() {
  local service="$1" container_port="$2"
  docker compose --project-directory "$ROOT" port "$service" "$container_port" 2>/dev/null | sed -n 's/.*:\([0-9]*\)$/\1/p' | head -1
}

resolve_port() {
  local preferred="$1" service="$2" container_port="$3" pid existing

  if [ -n "$service" ]; then
    existing=$(compose_published_port "$service" "$container_port")
    if [ -n "$existing" ]; then
      CLAIMED_PORTS="$CLAIMED_PORTS $existing"; RESOLVED_PORT="$existing"; return
    fi
  fi

  if ! port_claimed "$preferred"; then
    if ! port_busy "$preferred"; then
      CLAIMED_PORTS="$CLAIMED_PORTS $preferred"; RESOLVED_PORT="$preferred"; return
    fi
    if pid=$(repo_listener_pid "$preferred"); then
      kill -9 "$pid" 2>/dev/null || true
      sleep 1
      CLAIMED_PORTS="$CLAIMED_PORTS $preferred"; RESOLVED_PORT="$preferred"; return
    fi
  fi

  local port=$((preferred + 1))
  while [ "$port" -lt $((preferred + 50)) ] && { port_claimed "$port" || port_busy "$port"; }; do
    port=$((port + 1))
  done
  warn "포트 ${preferred} 사용 중 — ${port}로 우회"
  CLAIMED_PORTS="$CLAIMED_PORTS $port"; RESOLVED_PORT="$port"
}

# ── Docker ───────────────────────────────────────────────────
if ! docker_running; then
  echo "Docker is not running. Starting Docker Desktop..."
  open -a Docker
  for i in $(seq 1 60); do
    sleep 1
    docker_running && { ok "Docker ready (${i}s)"; break; }
    [ "$i" -eq 60 ] && die "Docker가 60초 안에 뜨지 않았습니다." ""
  done
fi

# 인프라가 OOM으로 죽는 가장 흔한 원인은 다른 프로젝트 컨테이너가 Docker VM 메모리를 먼저 차지한 경우다.
# (elasticsearch가 기동 중 exit 137로 죽고 "시작 타임아웃"으로만 보인다.) 막지는 않고 미리 알린다.
"$ROOT/scripts/dev/doctor.sh" --memory-only || true

echo "Resolving ports..."
resolve_port 5432 postgres 5432;          POSTGRES_PORT=$RESOLVED_PORT
resolve_port 6379 redis 6379;             REDIS_PORT=$RESOLVED_PORT
resolve_port 27017 mongodb 27017;         MONGODB_PORT=$RESOLVED_PORT
resolve_port 9200 elasticsearch 9200;     ELASTICSEARCH_PORT=$RESOLVED_PORT
resolve_port 16686 jaeger 16686;          JAEGER_UI_PORT=$RESOLVED_PORT
resolve_port 4318 jaeger 4318;            OTLP_PORT=$RESOLVED_PORT
resolve_port 1025 mailhog 1025;           MAILHOG_SMTP_PORT=$RESOLVED_PORT
resolve_port 8025 mailhog 8025;           MAILHOG_WEB_PORT=$RESOLVED_PORT
resolve_port 8080;                        API_PORT=$RESOLVED_PORT
[ "$WITH_MSA" = true ] || { resolve_port 8081; WORKER_PORT=$RESOLVED_PORT; }
resolve_port 3000;                        WEB_PORT=$RESOLVED_PORT
if [ "$WITH_KAFKA" = true ]; then
  resolve_port 9092 kafka 9092;           KAFKA_PORT=$RESOLVED_PORT
  resolve_port 29092 kafka 29092;         KAFKA_EXTERNAL_PORT=$RESOLVED_PORT
fi
export POSTGRES_PORT REDIS_PORT MONGODB_PORT ELASTICSEARCH_PORT JAEGER_UI_PORT OTLP_PORT \
       MAILHOG_SMTP_PORT MAILHOG_WEB_PORT KAFKA_PORT KAFKA_EXTERNAL_PORT

mkdir -p "$LOG_DIR"
cat > "$STATE_FILE" <<EOF
MODE=$MODE
API_PORT=$API_PORT
WORKER_PORT=${WORKER_PORT:-}
WEB_PORT=$WEB_PORT
ELASTICSEARCH_PORT=$ELASTICSEARCH_PORT
MAILHOG_WEB_PORT=$MAILHOG_WEB_PORT
JAEGER_UI_PORT=$JAEGER_UI_PORT
KAFKA_EXTERNAL_PORT=${KAFKA_EXTERNAL_PORT:-}
EOF

# ── 대기 ─────────────────────────────────────────────────────
# wait_for <이름> <로그파일> <성공조건함수> <PID> <타임아웃초>
wait_for() {
  local name="$1" logfile="$2" check_fn="$3" pid="$4" timeout="${5:-90}" elapsed=0
  info "Waiting for ${name}..."
  while [ $elapsed -lt "$timeout" ]; do
    sleep 2
    elapsed=$((elapsed + 2))
    # 체크 함수는 자기 명령의 잡음을 스스로 막는다. 여기서 2>/dev/null로 감싸면 체크 안에서 부른 die의 메시지까지 사라진다.
    if $check_fn; then
      ok "${name} OK  (${elapsed}s)"
      return 0
    fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      die "${name} 프로세스가 예기치 않게 종료되었습니다 (${elapsed}s 경과)" "$logfile"
    fi
    if [ $((elapsed % 10)) -eq 0 ]; then
      echo -e "  ${DIM}  still waiting... ${elapsed}s / ${timeout}s${NC}"
      # 기동 실패를 뜻하는 표식만 본다. "Exception" 같은 넓은 패턴은 기동을 막지 않는 경고성 스택트레이스
      # (예: 기본 모드엔 Kafka가 없어 KafkaAdmin이 남기는 TimeoutException)에도 걸려 정상 기동을 실패로 판정했다.
      if [ -f "$logfile" ] && grep -qE "BUILD FAILED|APPLICATION FAILED TO START|Application run failed" "$logfile" 2>/dev/null; then
        die "${name} 시작 중 오류 감지" "$logfile"
      fi
    fi
  done
  die "${name} 시작 타임아웃 (${timeout}s)" "$logfile"
}

# 컨테이너가 OOM으로 죽었으면 타임아웃까지 기다리지 말고 바로 원인을 알린다.
# optional이면 죽어도 중단하지 않고 경고만 한다(OPTIONAL_DOWN에 모은다).
OPTIONAL_DOWN=""
container_ready() {
  local service="$1" optional="${2:-}" cid state
  cid=$(docker compose --project-directory "$ROOT" ps -aq "$service" 2>/dev/null | head -1)
  [ -z "$cid" ] && return 1
  state=$(docker inspect "$cid" --format '{{.State.Status}} {{.State.OOMKilled}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' 2>/dev/null)
  if [ -n "$optional" ] && [[ "$state" == exited* ]]; then
    warn "${service} 컨테이너가 종료됐다$([[ "$state" == "exited true"* ]] && echo '(메모리 부족 OOM)') — ${optional}. 계속 진행한다"
    OPTIONAL_DOWN="$OPTIONAL_DOWN $service"
    return 0
  fi
  case "$state" in
    "exited true"*) die "${service} 컨테이너가 메모리 부족(OOM)으로 종료됐습니다 — scripts/dev/doctor.sh 로 Docker 메모리를 확인하세요" "" ;;
    exited*)        die "${service} 컨테이너가 종료됐습니다 — docker compose logs ${service}" "" ;;
    *" healthy")    return 0 ;;
    *)              return 1 ;;
  esac
}

# ── 1. infra ─────────────────────────────────────────────────
echo ""
BASE_INFRA="postgres redis mongodb elasticsearch jaeger mailhog"
case "$MODE" in
  msa)
    echo "1/4  Starting infra (MSA: ${BASE_INFRA} + kafka + worker-market/event/alert)..."
    info "MSA 이미지 빌드 (변경 없으면 캐시)..."
    docker compose --profile msa build --quiet 2>&1 || warn "일부 이미지 빌드 실패 — 계속 진행"
    docker compose up -d $BASE_INFRA 2>&1 | grep -v "^$" || true
    docker compose --profile msa up -d --no-build 2>&1 | grep -v "^$" || true
    ;;
  kafka)
    echo "1/4  Starting infra (Kafka: ${BASE_INFRA} + kafka + market-gateway)..."
    info "Kafka 프로파일 이미지 빌드 (변경 없으면 캐시)..."
    docker compose --profile kafka build --quiet 2>&1 || warn "일부 이미지 빌드 실패 — 계속 진행"
    docker compose up -d $BASE_INFRA 2>&1 | grep -v "^$" || true
    docker compose --profile kafka up -d --no-build 2>&1 | grep -v "^$" || true
    ;;
  *)
    echo "1/4  Starting infra (${BASE_INFRA})..."
    docker compose up -d $BASE_INFRA 2>&1 | grep -v "^$" || true
    ;;
esac
if [ "$WITH_PINPOINT" = true ]; then
  docker compose --profile pinpoint up -d 2>&1 | grep -v "^$" || true
fi

postgres_ready() { docker compose exec -T postgres pg_isready -U monticker -q > /dev/null 2>&1; }
wait_for "postgres" "" postgres_ready "" 60
mongodb_ready() { container_ready mongodb; }
wait_for "mongodb" "" mongodb_ready "" 60
# ES는 JVM 워밍업으로 느리다. 없어도 API는 뜨고 검색은 DB로 폴백하므로, 죽으면(대개 OOM) 경고만 하고 계속한다.
elasticsearch_ready() { container_ready elasticsearch "검색은 DB 폴백으로 동작, /actuator/health는 DOWN으로 보인다"; }
wait_for "elasticsearch" "" elasticsearch_ready "" 120
# mailhog 이미지는 healthcheck가 없다 — Web UI API로 확인
mailhog_ready() { /usr/bin/curl -sf "http://localhost:${MAILHOG_WEB_PORT}/api/v2/messages" > /dev/null 2>&1; }
wait_for "mailhog" "" mailhog_ready "" 30
if [ "$WITH_KAFKA" = true ]; then
  kafka_ready() { container_ready kafka; }
  wait_for "kafka" "" kafka_ready "" 90
fi
if [ "$WITH_PINPOINT" = true ]; then
  pinpoint_ready() { container_ready pinpoint-web; }
  info "Pinpoint는 HBase 초기화로 최대 3분 걸린다"
  wait_for "Pinpoint" "" pinpoint_ready "" 180
fi

# 호스트(bootRun)에서 Kafka에 붙을 때는 EXTERNAL 리스너(localhost:${KAFKA_EXTERNAL_PORT})를 써야 한다.
# 9092는 PLAINTEXT://kafka:9092를 광고해서, 부트스트랩은 되지만 이후 메타데이터의 "kafka"를 호스트가 풀지 못해
# 발행·소비가 전부 실패한다(docker-compose.yml의 KAFKA_ADVERTISED_LISTENERS).
HOST_KAFKA_BROKERS="localhost:${KAFKA_EXTERNAL_PORT}"

# ── 2. api ───────────────────────────────────────────────────
echo ""
echo "2/4  Starting API (port ${API_PORT})..."
cd "$ROOT/backend/api"
API_ENV="OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:${OTLP_PORT} PINPOINT_ENABLE=${WITH_PINPOINT}"
API_ENV="$API_ENV DB_URL=jdbc:postgresql://localhost:${POSTGRES_PORT}/monticker REDIS_HOST=localhost REDIS_PORT=${REDIS_PORT}"
API_ENV="$API_ENV MONGODB_URI=mongodb://monticker:monticker@localhost:${MONGODB_PORT}/monticker?authSource=admin"
API_ENV="$API_ENV ELASTICSEARCH_URI=http://localhost:${ELASTICSEARCH_PORT}"
API_ENV="$API_ENV ALLOWED_ORIGINS=http://localhost:${WEB_PORT} APP_BASE_URL=http://localhost:${WEB_PORT}"
# API는 호스트에서 돌므로 도커 내부 호스트명 "mailhog"가 아니라 노출 포트로 붙는다. smtp.auth=true가 고정이라
# 자격증명 문자열은 있어야 하지만 MailHog는 검사하지 않는다.
API_ENV="$API_ENV MAIL_HOST=localhost MAIL_PORT=${MAILHOG_SMTP_PORT} MAIL_USERNAME=test MAIL_PASSWORD=test"
# docs/security-review.md C1 — 커밋된 개발용 JWT/암호화 키를 InsecureSecretGuard가 막는다. 로컬 dev만 명시적으로 허용.
API_ENV="$API_ENV ALLOW_INSECURE_DEV_SECRETS=true"
[ "$WITH_KAFKA" = true ] && API_ENV="$API_ENV KAFKA_BROKERS=${HOST_KAFKA_BROKERS}"

eval "$API_ENV ./gradlew bootRun --console=plain -q --args='--server.port=${API_PORT}'" > "$LOG_DIR/api.log" 2>&1 &
API_PID=$!
api_ready() {
  /usr/bin/curl -sf "http://localhost:${API_PORT}/actuator/health/readiness" > /dev/null 2>&1 ||
  grep -q "Started ApiApplication" "$LOG_DIR/api.log" 2>/dev/null
}
wait_for "API" "$LOG_DIR/api.log" api_ready "$API_PID" 150

# ── 3. worker ────────────────────────────────────────────────
echo ""
if [ "$WITH_MSA" = true ]; then
  # MSA 모드에서는 worker-market/event/alert 컨테이너가 워커 역할을 전부 맡는다. 여기서 role=all 워커를 또 띄우면
  # Mock 시세 생산과 market.ticks 소비가 중복된다(k8s base에서 같은 결함을 고쳤다 — 73a4b41).
  echo "3/4  Worker: 컨테이너(worker-market/event/alert)가 담당 — 호스트 워커는 띄우지 않는다"
else
  echo "3/4  Starting Worker (port ${WORKER_PORT})..."
  cd "$ROOT/backend/worker"
  # 기본: INGESTION_SOURCE=internal (MockPriceGenerator). Kafka: market-gateway → Kafka → Worker.
  WORKER_ENV="OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:${OTLP_PORT} PINPOINT_ENABLE=${WITH_PINPOINT}"
  WORKER_ENV="$WORKER_ENV DB_URL=jdbc:postgresql://localhost:${POSTGRES_PORT}/monticker REDIS_HOST=localhost REDIS_PORT=${REDIS_PORT}"
  WORKER_ENV="$WORKER_ENV ELASTICSEARCH_URI=http://localhost:${ELASTICSEARCH_PORT}"
  # 워커도 메일을 보낸다(알림 이메일 폴백). 기본값이 smtp.gmail.com이라 안 넘기면 로컬에서 실제 Gmail로 시도한다.
  WORKER_ENV="$WORKER_ENV MAIL_HOST=localhost MAIL_PORT=${MAILHOG_SMTP_PORT} MAIL_USERNAME=test MAIL_PASSWORD=test"
  [ "$WITH_KAFKA" = true ] && WORKER_ENV="$WORKER_ENV INGESTION_SOURCE=kafka KAFKA_BROKERS=${HOST_KAFKA_BROKERS}"

  eval "$WORKER_ENV ./gradlew bootRun --console=plain -q --args='--server.port=${WORKER_PORT}'" > "$LOG_DIR/worker.log" 2>&1 &
  WORKER_PID=$!
  worker_ready() {
    /usr/bin/curl -sf "http://localhost:${WORKER_PORT}/actuator/health/readiness" > /dev/null 2>&1 ||
    grep -q "Started WorkerApplication" "$LOG_DIR/worker.log" 2>/dev/null
  }
  wait_for "Worker" "$LOG_DIR/worker.log" worker_ready "$WORKER_PID" 120
fi

# ── 4. web ───────────────────────────────────────────────────
echo ""
echo "4/4  Starting Web (port ${WEB_PORT})..."
cd "$ROOT"
pnpm install --frozen-lockfile --ignore-scripts > "$LOG_DIR/web-install.log" 2>&1 ||
  warn "pnpm install 실패 — 기존 node_modules로 진행 (logs/web-install.log)"
# .env.local의 NEXT_PUBLIC_API_URL은 8080 고정이다. API가 다른 포트로 떴는데 이걸 안 넘기면 브라우저의 /api/*가
# 우연히 8080을 쓰는 다른 프로세스로 가서 영문 모를 401이 난다. Next.js는 process.env 값을 .env.local보다 우선한다.
NEXT_PUBLIC_API_URL="http://localhost:${API_PORT}" pnpm --filter @monticker/web exec next dev -p "$WEB_PORT" > "$LOG_DIR/web.log" 2>&1 &
WEB_PID=$!
web_ready() { /usr/bin/curl -sf -o /dev/null "http://localhost:${WEB_PORT}" 2>/dev/null; }
wait_for "Web" "$LOG_DIR/web.log" web_ready "$WEB_PID" 90

# ── ready ────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}========================================"
echo "  monticker is running  (mode: ${MODE}$([ "$WITH_PINPOINT" = true ] && echo ' + pinpoint'))"
echo ""
echo "  Web     → http://localhost:${WEB_PORT}"
echo "  API     → http://localhost:${API_PORT}"
[ -n "${WORKER_PORT:-}" ] && echo "  Worker  → http://localhost:${WORKER_PORT}"
echo "  Jaeger  → http://localhost:${JAEGER_UI_PORT}"
echo "  MailHog → http://localhost:${MAILHOG_WEB_PORT}  (인증 메일 / 비밀번호 재설정 링크)"
[ "$WITH_KAFKA" = true ] && echo "  Kafka   → ${HOST_KAFKA_BROKERS}"
[ "$WITH_PINPOINT" = true ] && echo "  Pinpoint → http://localhost:18080"
echo ""
case "$MODE" in
  default) echo "  시세: MockPriceGenerator (내부). Kafka 경로 기능은 --kafka 로 띄울 것" ;;
  *)       echo "  시세: Go market-gateway → Kafka → Worker" ;;
esac
echo -e "========================================${NC}"
[ -n "$OPTIONAL_DOWN" ] && warn "뜨지 못한 인프라:${OPTIONAL_DOWN} — scripts/dev/status.sh 로 원인(메모리 등) 확인"
echo ""
echo "  상태 확인: scripts/dev/status.sh     로그: tail -f logs/{api,worker,web}.log"
echo "  차트 백필: scripts/data/backfill-candles.py (처음 실행 또는 DB 초기화 후)"
echo ""
echo "Press Ctrl-C to stop all."

wait
