#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'

# ── 옵션 파싱 ─────────────────────────────────────────────────
WITH_PINPOINT=false
for arg in "$@"; do
  case "$arg" in
    --pinpoint) WITH_PINPOINT=true ;;
    --help|-h)
      echo "Usage: ./dev.sh [--pinpoint]"
      echo "  --pinpoint   Pinpoint APM 포함 기동 (HBase 초기화 2~3분 소요)"
      exit 0 ;;
  esac
done

# ── cleanup on Ctrl-C ─────────────────────────────────────────
cleanup() {
  echo ""
  echo "Stopping..."
  kill "$API_PID" "$WORKER_PID" "$WEB_PID" 2>/dev/null
  if [ "$WITH_PINPOINT" = true ]; then
    docker compose --profile pinpoint stop 2>/dev/null
  else
    docker compose stop 2>/dev/null
  fi
  wait 2>/dev/null
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
  # 남은 프로세스 정리
  kill "$API_PID" "$WORKER_PID" "$WEB_PID" 2>/dev/null || true
  exit 1
}

# ── 프로세스 대기 (타임아웃 + 실시간 로그) ─────────────────────
# wait_for <이름> <로그파일> <성공조건함수> <PID> <타임아웃초>
wait_for() {
  local name="$1"
  local logfile="$2"
  local check_fn="$3"   # 함수명: 성공 시 return 0
  local pid="$4"
  local timeout="${5:-90}"
  local elapsed=0

  echo -e "  ${CYAN}Waiting for ${name}...${NC}"

  while [ $elapsed -lt $timeout ]; do
    sleep 2
    elapsed=$((elapsed + 2))

    # 성공 확인
    if $check_fn 2>/dev/null; then
      echo -e "  ${GREEN}${name} OK${NC}  (${elapsed}s, log: ${logfile})"
      return 0
    fi

    # 프로세스 죽었는지 확인
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      die "${name} 프로세스가 예기치 않게 종료되었습니다 (${elapsed}s 경과)" "$logfile"
    fi

    # 10초마다 진행 상황 표시
    if [ $((elapsed % 10)) -eq 0 ]; then
      echo -e "  ${YELLOW}  still waiting... ${elapsed}s / ${timeout}s${NC}"
      # 오류 키워드가 로그에 있으면 즉시 실패
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
echo "Clearing ports 3000, 4318 and 8080..."
lsof -ti :3000 | xargs kill -9 2>/dev/null || true
lsof -ti :8080 | xargs kill -9 2>/dev/null || true
sleep 1

# ── 1. infra ─────────────────────────────────────────────────
echo ""
if [ "$WITH_PINPOINT" = true ]; then
  echo "1/4  Starting infra (postgres + redis + jaeger + pinpoint)..."
  docker compose up -d postgres redis jaeger 2>&1 | grep -v "^$" || true
  docker compose --profile pinpoint up -d 2>&1 | grep -v "^$" || true
else
  echo "1/4  Starting infra (postgres + redis + jaeger)..."
  docker compose up -d postgres redis jaeger 2>&1 | grep -v "^$" || true
fi

postgres_ready() { docker compose exec postgres pg_isready -U monticker -q 2>/dev/null; }
wait_for "postgres" "/dev/null" postgres_ready "" 60

if [ "$WITH_PINPOINT" = true ]; then
  pinpoint_ready() {
    docker compose ps pinpoint-web 2>/dev/null | grep -q "healthy"
  }
  echo ""
  echo "  Waiting for Pinpoint (HBase 초기화 중, 최대 3분)..."
  wait_for "Pinpoint" "/dev/null" pinpoint_ready "" 180
fi

# ── 2. api ───────────────────────────────────────────────────
echo ""
echo "2/4  Starting API (port 8080)..."
mkdir -p "$ROOT/logs"
cd "$ROOT/backend/api"
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
PINPOINT_ENABLE=${WITH_PINPOINT} \
  ./gradlew bootRun --console=plain -q > "$ROOT/logs/api.log" 2>&1 &
API_PID=$!

api_ready() {
  /usr/bin/curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1 ||
  grep -q "Started ApiApplication" "$ROOT/logs/api.log" 2>/dev/null
}
wait_for "API" "$ROOT/logs/api.log" api_ready "$API_PID" 120

# ── 3. worker ────────────────────────────────────────────────
echo ""
echo "3/4  Starting Worker..."
cd "$ROOT/backend/worker"
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
PINPOINT_ENABLE=${WITH_PINPOINT} \
  ./gradlew bootRun --console=plain -q > "$ROOT/logs/worker.log" 2>&1 &
WORKER_PID=$!

worker_ready() {
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
echo "  Web    → http://localhost:3000"
echo "  API    → http://localhost:8080"
echo "  Jaeger → http://localhost:16686"
if [ "$WITH_PINPOINT" = true ]; then
echo "  Pinpoint → http://localhost:18080"
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
