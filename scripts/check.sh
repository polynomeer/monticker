#!/usr/bin/env bash
# PR 올리기 전에 CI(backend-ci, web-ci)가 돌리는 검사를 로컬에서 같은 명령으로 돌린다.
# 실패해도 끝까지 돌고 마지막에 요약한다 — 하나 고치고 다시 돌리는 왕복을 줄인다.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"   # audit-scope.js는 현재 디렉터리에서 pnpm audit을 돈다

usage() {
  cat <<EOF
Usage: scripts/check.sh [options] [targets...]

targets (기본: 전부)
  api        backend/api   ./gradlew test
  worker     backend/worker ./gradlew test
  web        pnpm audit(스코프) + lint + test (apps/web)

options
  --full     CI와 똑같이: backend integrationTest(Testcontainers, Docker 필요) + web build 추가
  -h, --help 이 도움말
EOF
}

FULL=false
TARGETS=()
for arg in "$@"; do
  case "$arg" in
    --full)            FULL=true ;;
    api|worker|web)    TARGETS+=("$arg") ;;
    -h|--help)         usage; exit 0 ;;
    *)                 echo "알 수 없는 인자: $arg" >&2; usage >&2; exit 2 ;;
  esac
done
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(api worker web)

if [ -t 1 ]; then RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'; else RED=''; GREEN=''; CYAN=''; NC=''; fi
RESULTS=()
FAILED=0
step() {  # step <이름> <명령...>
  local name="$1"; shift
  echo -e "\n${CYAN}▶ ${name}${NC}"
  local start=$SECONDS
  if "$@"; then
    RESULTS+=("${GREEN}✓${NC} ${name} ($((SECONDS - start))s)")
  else
    RESULTS+=("${RED}✗${NC} ${name} ($((SECONDS - start))s)")
    FAILED=$((FAILED + 1))
  fi
}

for t in "${TARGETS[@]}"; do
  case "$t" in
    api|worker)
      step "$t: unit test" bash -c "cd '$ROOT/backend/$t' && ./gradlew test --console=plain -q"
      [ "$FULL" = true ] && step "$t: integration test" bash -c "cd '$ROOT/backend/$t' && ./gradlew integrationTest --console=plain -q"
      ;;
    web)
      step "web: install" bash -c "cd '$ROOT' && pnpm install --frozen-lockfile --silent"
      # 허용 목록은 web-ci.yml과 같아야 한다 — 바꾸면 거기도 바꿀 것.
      step "web: audit (apps/web, packages/)" node "$ROOT/scripts/ci/audit-scope.js" apps/web packages/ --allow braces
      step "web: lint" pnpm --dir "$ROOT" --filter @monticker/web lint
      step "web: test" pnpm --dir "$ROOT" --filter @monticker/web test
      [ "$FULL" = true ] && step "web: build" pnpm --dir "$ROOT" --filter @monticker/web build
      ;;
  esac
done

echo -e "\n── 요약 ──"
for r in "${RESULTS[@]}"; do echo -e "  $r"; done
if [ "$FAILED" -gt 0 ]; then
  echo -e "${RED}${FAILED}개 실패${NC}"
  [ "$FULL" = false ] && echo "  (CI는 integrationTest와 web build도 돈다 — 머지 전엔 --full)"
  exit 1
fi
echo -e "${GREEN}모두 통과${NC}$([ "$FULL" = false ] && echo '  — CI와 완전히 같게는 --full')"
