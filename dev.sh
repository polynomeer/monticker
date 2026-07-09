#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'

# ── 옵션 파싱 ─────────────────────────────────────────────────
WITH_PINPOINT=false
WITH_KAFKA=false
WITH_MSA=false

for arg in "$@"; do
  case "$arg" in
    --pinpoint) WITH_PINPOINT=true ;;
    --kafka)    WITH_KAFKA=true ;;
    --msa)      WITH_MSA=true; WITH_KAFKA=true ;;  # MSA는 Kafka 필요
    --help|-h)
      echo "Usage: ./dev.sh [options]"
      echo ""
      echo "Options:"
      echo "  (없음)       기본 모드 — API + Worker (MockPriceGenerator) + Web"
      echo "  --kafka      Kafka 모드 — Kafka + market-gateway + broadcast-gateway 추가"
      echo "               Worker가 실제 시세 파이프라인(Go→Kafka→Worker)으로 동작"
      echo "  --msa        MSA 모드  — --kafka + quant-engine + trading-service 컨테이너 추가"
      echo "               API가 로컬 서비스 대신 MSA 서비스로 위임"
      echo "  --pinpoint   Pinpoint APM 포함 기동 (HBase 초기화 2~3분 소요)"
      exit 0 ;;
  esac
done

# ── cleanup on Ctrl-C ─────────────────────────────────────────
cleanup() {
  echo ""
  echo "Stopping..."
  kill "$API_PID" "$WORKER_PID" "$WEB_PID" 2>/dev/null || true
  if [ "$WITH_MSA" = true ]; then
    docker compose --profile msa stop 2>/dev/null || true
  elif [ "$WITH_KAFKA" = true ]; then
    docker compose --profile kafka stop 2>/dev/null || true
    docker compose stop postgres redis jaeger 2>/dev/null || true
  elif [ "$WITH_PINPOINT" = true ]; then
    docker compose --profile pinpoint stop 2>/dev/null || true
  else
    docker compose stop 2>/dev/null || true
  fi
  wait 2>/dev/null || true
  echo "Done."
  exit 0
}
trap cleanup INT TERM

# ── 로그와 함께 실패 종료 ──────────────────────────────────────
die() {
  local msg="$1"
  local logfile="$2"
  echo ""
  echo -e "${RED}[FAILED] ${msg}${NC}"
  if [ -n "$logfile" ] && [ -f "$logfile" ]; then
    echo -e "${YELLOW}──── 마지막 20줄 ($logfile) ────${NC}"
    tail -20 "$logfile"
    echo -e "${YELLOW}────────────────────────────────${NC}"
    echo "전체 로그: $logfile"
  fi
  kill "$API_PID" "$WORKER_PID" "$WEB_PID" 2>/dev/null || true
  exit 1
}

# ── 프로세스 대기 (타임아웃 + 실시간 로그) ─────────────────────
# wait_for <이름> <로그파일> <성공조건함수> <PID> <타임아웃초>
wait_for() {
  local name="$1"
  local logfile="$2"
  local check_fn="$3"
  local pid="$4"
  local timeout="${5:-90}"
  local elapsed=0

  echo -e "  ${CYAN}Waiting for ${name}...${NC}"

  while [ $elapsed -lt $timeout ]; do
    sleep 2
    elapsed=$((elapsed + 2))

    if $check_fn 2>/dev/null; then
      echo -e "  ${GREEN}${name} OK${NC}  (${elapsed}s)"
      return 0
    fi

    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      die "${name} 프로세스가 예기치 않게 종료되었습니다 (${elapsed}s 경과)" "$logfile"
    fi

    if [ $((elapsed % 10)) -eq 0 ]; then
      echo -e "  ${YELLOW}  still waiting... ${elapsed}s / ${timeout}s${NC}"
      if grep -qiE "BUILD FAILED|Exception|ERROR.*Application run failed" "$logfile" 2>/dev/null; then
        die "${name} 시작 중 오류 감지" "$logfile"
      fi
    fi
  done

  die "${name} 시작 타임아웃 (${timeout}s)" "$logfile"
}

# ── Docker ───────────────────────────────────────────────────
if ! docker info > /dev/null 2>&1; then
  echo "Docker is not running. Starting Docker Desktop..."
  open -a Docker
  for i in $(seq 1 60); do
    sleep 1
    docker info > /dev/null 2>&1 && { echo -e "  ${GREEN}Docker ready${NC} (${i}s)"; break; }
    [ "$i" -eq 60 ] && die "Docker did not start in time." ""
  done
fi

# ── 포트 정리 ────────────────────────────────────────────────
echo "Clearing ports 3000, 8080, 8081..."
lsof -ti :3000 | xargs kill -9 2>/dev/null || true
lsof -ti :8080 | xargs kill -9 2>/dev/null || true
lsof -ti :8081 | xargs kill -9 2>/dev/null || true
sleep 1

# ── 1. infra ─────────────────────────────────────────────────
mkdir -p "$ROOT/logs"
echo ""

if [ "$WITH_MSA" = true ]; then
  echo "1/4  Starting infra (MSA 모드: postgres + redis + jaeger + kafka + quant-engine + trading-service)..."
  # MSA 이미지 사전 빌드 확인
  if ! docker image inspect monticker-quant-engine > /dev/null 2>&1 || \
     ! docker image inspect monticker-trading-service > /dev/null 2>&1; then
    echo ""
    echo -e "${YELLOW}[INFO] MSA 서비스 이미지가 없습니다. 먼저 빌드를 실행합니다 (최초 1회)...${NC}"
    echo "       (이후 실행에서는 캐시를 사용하므로 빠릅니다)"
    echo ""
    docker compose --profile msa build 2>&1 | grep -E "^#|building|DONE|ERROR" || true
    echo ""
  fi
  docker compose up -d postgres redis jaeger 2>&1 | grep -v "^$" || true
  docker compose --profile msa up -d --no-build 2>&1 | grep -v "^$" || true

elif [ "$WITH_KAFKA" = true ]; then
  echo "1/4  Starting infra (Kafka 모드: postgres + redis + jaeger + kafka + market-gateway + broadcast-gateway)..."
  if ! docker image inspect monticker-market-gateway > /dev/null 2>&1; then
    echo -e "${YELLOW}[INFO] market-gateway 이미지 없음. 빌드 중 (최초 1회)...${NC}"
    docker compose --profile kafka build market-gateway broadcast-gateway 2>&1 | grep -E "^#|building|DONE|ERROR" || true
  fi
  docker compose up -d postgres redis jaeger 2>&1 | grep -v "^$" || true
  docker compose --profile kafka up -d --no-build 2>&1 | grep -v "^$" || true

elif [ "$WITH_PINPOINT" = true ]; then
  echo "1/4  Starting infra (postgres + redis + jaeger + pinpoint)..."
  docker compose up -d postgres redis jaeger 2>&1 | grep -v "^$" || true
  docker compose --profile pinpoint up -d 2>&1 | grep -v "^$" || true

else
  echo "1/4  Starting infra (postgres + redis + jaeger)..."
  docker compose up -d postgres redis jaeger 2>&1 | grep -v "^$" || true
fi

postgres_ready() { docker compose exec postgres pg_isready -U monticker -q 2>/dev/null; }
wait_for "postgres" "/dev/null" postgres_ready "" 60

if [ "$WITH_KAFKA" = true ]; then
  # healthcheck 통과 여부로 확인 (이미지별 bin 경로 차이 회피)
  kafka_ready() {
    docker compose ps kafka 2>/dev/null | grep -q "healthy"
  }
  wait_for "kafka" "/dev/null" kafka_ready "" 90
fi

if [ "$WITH_PINPOINT" = true ]; then
  pinpoint_ready() { docker compose ps pinpoint-web 2>/dev/null | grep -q "healthy"; }
  echo ""
  echo "  Waiting for Pinpoint (HBase 초기화 중, 최대 3분)..."
  wait_for "Pinpoint" "/dev/null" pinpoint_ready "" 180
fi

if [ "$WITH_MSA" = true ]; then
  quant_ready()   { /usr/bin/curl -sf http://localhost:8082/actuator/health > /dev/null 2>&1; }
  trading_ready() { /usr/bin/curl -sf http://localhost:8083/actuator/health > /dev/null 2>&1; }
  wait_for "quant-engine"    "/dev/null" quant_ready   "" 60
  wait_for "trading-service" "/dev/null" trading_ready "" 60
fi

# ── 2. api ───────────────────────────────────────────────────
echo ""
echo "2/4  Starting API (port 8080)..."
cd "$ROOT/backend/api"

# MSA 모드: TRADING_SERVICE_URL / QUANT_ENGINE_URL 활성화
# Kafka 모드: KAFKA_BROKERS 설정 (Outbox 발행 정상화)
API_ENV="OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 PINPOINT_ENABLE=${WITH_PINPOINT}"
if [ "$WITH_MSA" = true ]; then
  API_ENV="$API_ENV TRADING_SERVICE_URL=http://localhost:8083 QUANT_ENGINE_URL=http://localhost:8082"
fi
if [ "$WITH_KAFKA" = true ]; then
  API_ENV="$API_ENV KAFKA_BROKERS=localhost:9092"
fi

eval "$API_ENV ./gradlew bootRun --console=plain -q" > "$ROOT/logs/api.log" 2>&1 &
API_PID=$!

api_ready() {
  /usr/bin/curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1 ||
  grep -q "Started ApiApplication" "$ROOT/logs/api.log" 2>/dev/null
}
wait_for "API" "$ROOT/logs/api.log" api_ready "$API_PID" 120

# ── 3. worker ────────────────────────────────────────────────
echo ""
echo "3/4  Starting Worker (port 8081)..."
cd "$ROOT/backend/worker"

# Kafka 모드: INGESTION_SOURCE=kafka (Go market-gateway → Kafka → Worker)
# 기본 모드: INGESTION_SOURCE=internal (MockPriceGenerator)
WORKER_ENV="OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 PINPOINT_ENABLE=${WITH_PINPOINT}"
if [ "$WITH_KAFKA" = true ]; then
  WORKER_ENV="$WORKER_ENV INGESTION_SOURCE=kafka KAFKA_BROKERS=localhost:9092"
fi

eval "$WORKER_ENV ./gradlew bootRun --console=plain -q" > "$ROOT/logs/worker.log" 2>&1 &
WORKER_PID=$!

worker_ready() {
  /usr/bin/curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1 ||
  grep -q "Started WorkerApplication" "$ROOT/logs/worker.log" 2>/dev/null
}
wait_for "Worker" "$ROOT/logs/worker.log" worker_ready "$WORKER_PID" 90

# ── 4. web ───────────────────────────────────────────────────
echo ""
echo "4/4  Starting Web (port 3000)..."
cd "$ROOT/apps/web"
pnpm install --ignore-scripts --frozen-lockfile 2>/dev/null || true
pnpm dev > "$ROOT/logs/web.log" 2>&1 &
WEB_PID=$!

web_ready() { /usr/bin/curl -sf http://localhost:3000 > /dev/null 2>&1; }
wait_for "Web" "$ROOT/logs/web.log" web_ready "$WEB_PID" 60

# ── ready ────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}========================================"
echo "  monticker is running"
echo ""
echo "  Web    → http://localhost:3000"
echo "  API    → http://localhost:8080"
echo "  Worker → http://localhost:8081"
echo "  Jaeger → http://localhost:16686"
if [ "$WITH_KAFKA" = true ]; then
echo "  Kafka  → localhost:9092"
echo "  Broadcast-GW → ws://localhost:9090/ws"
fi
if [ "$WITH_MSA" = true ]; then
echo "  quant-engine    → http://localhost:8082"
echo "  trading-service → http://localhost:8083"
fi
if [ "$WITH_PINPOINT" = true ]; then
echo "  Pinpoint → http://localhost:18080"
fi
echo ""

if [ "$WITH_KAFKA" = true ]; then
echo "  시세 파이프라인: Go market-gateway → Kafka → Worker"
elif [ "$WITH_MSA" = true ]; then
echo "  시세 파이프라인: Go market-gateway → Kafka → Worker"
else
echo "  시세 파이프라인: MockPriceGenerator (내부)"
echo "  [참고] Kafka Outbox는 --kafka 또는 --msa 옵션 시 정상 동작"
fi
echo -e "========================================${NC}"
echo ""
echo "로그 실시간 보기:"
echo "  tail -f logs/api.log"
echo "  tail -f logs/worker.log"
echo "  tail -f logs/web.log"
echo ""
echo "차트 데이터 백필 (처음 실행 또는 DB 초기화 후):"
echo "  pip install -r scripts/requirements-backfill.txt"
echo "  python scripts/backfill-candles.py"
echo ""
echo "Press Ctrl-C to stop all."

wait
