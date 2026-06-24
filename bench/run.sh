#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# monticker API 벤치마크 실행기
#
# 사용법:
#   ./bench/run.sh                  # smoke 테스트 (기본)
#   ./bench/run.sh load             # 부하 테스트
#   ./bench/run.sh stress           # 스트레스 테스트
#   ./bench/run.sh spike            # 스파이크 테스트
#   ./bench/run.sh load --dashboard # 실시간 대시보드 포함
# ──────────────────────────────────────────────────────────────

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCENARIO="${1:-smoke}"
EXTRA_ARGS="${@:2}"

# ── 색상 ──────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

echo -e "${BOLD}${CYAN}"
echo "  ╔══════════════════════════════════════╗"
echo "  ║   monticker API Benchmark Runner     ║"
echo "  ╚══════════════════════════════════════╝"
echo -e "${NC}"

# ── 사전 확인 ─────────────────────────────────────────────────
if ! command -v k6 &> /dev/null; then
  echo -e "${RED}k6가 설치되어 있지 않습니다.${NC}"
  echo "  brew install k6"
  exit 1
fi

API_URL="${BASE_URL:-http://localhost:8080}"
echo -e "  대상 API: ${YELLOW}${API_URL}${NC}"
echo -e "  시나리오: ${YELLOW}${SCENARIO}${NC}"

# API health check
if ! /usr/bin/curl -sf "${API_URL}/actuator/health" > /dev/null 2>&1; then
  echo -e "\n${RED}API가 실행되지 않고 있습니다: ${API_URL}${NC}"
  echo "  ./dev.sh 로 먼저 서버를 실행해 주세요."
  exit 1
fi
echo -e "  API 상태: ${GREEN}정상${NC}"

# Rate limit 임시 완화 (벤치마크 IP 화이트리스트)
echo ""
echo -e "${BOLD}벤치마크 시작...${NC}"
echo -e "  결과: ${YELLOW}bench/results/latest_${SCENARIO}.json${NC}"
echo -e "  리포트: ${YELLOW}bench/reports/${SCENARIO}_*.html${NC}"
echo ""

# ── Rate limit Redis 카운터 초기화 ────────────────────────────
REDIS_CONTAINER=$(docker ps --format "{{.Names}}" | grep -i redis | head -1)
if [ -n "$REDIS_CONTAINER" ]; then
  echo -e "  Redis rate-limit 카운터 초기화 중..."
  docker exec "$REDIS_CONTAINER" redis-cli KEYS "rate:*" | \
    xargs -r docker exec "$REDIS_CONTAINER" redis-cli DEL > /dev/null 2>&1 || true
fi

# ── 대시보드 모드 확인 ────────────────────────────────────────
DASHBOARD_ARGS=""
if echo "$EXTRA_ARGS" | grep -q "\-\-dashboard"; then
  DASHBOARD_ARGS="--out web-dashboard"
  echo -e "  ${CYAN}대시보드: http://127.0.0.1:5665 에서 확인 가능${NC}"
  EXTRA_ARGS="${EXTRA_ARGS/--dashboard/}"
fi

# ── 실행 ──────────────────────────────────────────────────────
k6 run \
  --env SCENARIO="$SCENARIO" \
  --env BASE_URL="$API_URL" \
  --env REPORT_DIR="$ROOT/bench/reports" \
  --env RESULT_DIR="$ROOT/bench/results" \
  $DASHBOARD_ARGS \
  $EXTRA_ARGS \
  "$ROOT/bench/scenarios/api.js"

STATUS=$?

# ── 결과 요약 ────────────────────────────────────────────────
echo ""
if [ $STATUS -eq 0 ]; then
  echo -e "${GREEN}${BOLD}✓ 벤치마크 완료 — 모든 임계값 통과${NC}"
else
  echo -e "${RED}${BOLD}✗ 벤치마크 완료 — 임계값 초과 항목 있음${NC}"
fi

# 최신 HTML 리포트 경로 출력
LATEST_REPORT=$(ls -t "$ROOT/bench/reports/${SCENARIO}_"*.html 2>/dev/null | head -1)
if [ -n "$LATEST_REPORT" ]; then
  echo -e "  HTML 리포트: ${YELLOW}${LATEST_REPORT}${NC}"
  echo -e "  열기: ${CYAN}open '${LATEST_REPORT}'${NC}"
fi

exit $STATUS
