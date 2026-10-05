#!/usr/bin/env bash
# 로컬 개발 환경 사전 점검. 실패 항목이 있으면 종료 코드 1.
#   --memory-only  Docker VM 메모리만 본다(up.sh가 기동 전에 부른다)
#   --verbose      메모리를 많이 쓰는 컨테이너를 함께 보여준다
. "$(dirname "$0")/lib.sh"

MEMORY_ONLY=false
VERBOSE=false
for arg in "$@"; do
  case "$arg" in
    --memory-only) MEMORY_ONLY=true ;;
    --verbose)     VERBOSE=true ;;
    -h|--help)     sed -n '2,4p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)             echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
  esac
done

# 기본 스택(postgres·redis·mongodb·elasticsearch·jaeger·mailhog)이 새로 뜨는 데 필요한 Docker VM 여유분.
# ES 하나가 힙 512MB + 오버헤드로 ~1GB를 쓴다. 이보다 적으면 기동 중 OOM kill이 난다(ES exit 137 → "시작 타임아웃").
NEED_FREE_MB=${NEED_FREE_MB:-2048}

failures=0
check() {  # check <이름> <명령...>
  local name="$1"; shift
  if "$@" > /dev/null 2>&1; then
    ok "✓ $name"
  else
    echo -e "  ${RED}✗ $name${NC}"; failures=$((failures + 1))
  fi
}

to_mb() {  # docker stats 표기("1.5GiB" "512MiB" "900kB" "0B") → MB 정수
  awk -v v="$1" 'BEGIN {
    n = v + 0; u = v; sub(/^[0-9.]+/, "", u)
    if (u ~ /^G/) n *= 1024; else if (u ~ /^[kK]/) n /= 1024; else if (u == "B") n /= 1048576
    printf "%d", n
  }'
}

memory_check() {
  if ! docker_running; then warn "Docker가 꺼져 있어 메모리를 확인할 수 없다"; return 1; fi
  local total_mb used_mb=0 ours_mb=0 free_mb need stats name usage mb rc=0
  total_mb=$(( $(docker info --format '{{.MemTotal}}') / 1048576 ))
  stats=$(docker stats --no-stream --format '{{.Name}}	{{.MemUsage}}' 2>/dev/null)
  while IFS=$'\t' read -r name usage; do
    [ -z "$name" ] && continue
    mb=$(to_mb "${usage%% /*}")
    used_mb=$((used_mb + mb))
    # 이미 떠 있는 이 프로젝트 컨테이너는 재기동하지 않으니 새로 먹지 않는다 — 필요분에서 뺀다.
    case "$name" in monticker-*) ours_mb=$((ours_mb + mb)) ;; esac
  done <<< "$stats"
  free_mb=$((total_mb - used_mb))
  need=$((NEED_FREE_MB - ours_mb)); [ "$need" -lt 0 ] && need=0

  if [ "$free_mb" -lt "$need" ]; then
    warn "Docker VM 메모리 여유 ${free_mb}MB / 전체 ${total_mb}MB — 기본 스택에 ~${need}MB가 더 필요하다. 기동 중 OOM으로 죽을 수 있다"
    echo -e "  ${YELLOW}       안 쓰는 다른 프로젝트 컨테이너를 멈추거나 Docker Desktop → Settings → Resources → Memory를 늘릴 것${NC}" >&2
    VERBOSE=true
    rc=1
  else
    ok "✓ Docker VM 메모리 여유 ${free_mb}MB / 전체 ${total_mb}MB"
  fi
  if [ "$VERBOSE" = true ] && [ -n "$stats" ]; then
    echo "  메모리 상위 컨테이너:"
    while IFS=$'\t' read -r name usage; do
      [ -n "$name" ] && printf "%s\t%s\t%s\n" "$(to_mb "${usage%% /*}")" "$name" "${usage%% /*}"
    done <<< "$stats" | sort -rn | head -6 | while IFS=$'\t' read -r _ name usage; do
      printf "    %-36s %s\n" "$name" "$usage"
    done
  fi
  return $rc
}

if [ "$MEMORY_ONLY" = true ]; then
  memory_check
  exit $?
fi

echo "Tools"
check "docker 실행 중"             docker_running
check "docker compose v2"          docker compose version
check "java 21+"                   bash -c 'java -version 2>&1 | grep -Eq "version \"(2[1-9]|[3-9][0-9])"'
check "node 20+"                   bash -c 'node -v | grep -Eq "^v(2[0-9]|[3-9][0-9])\."'
check "pnpm"                       pnpm --version
check "curl, lsof"                 bash -c 'command -v curl && command -v lsof'
command -v k6 > /dev/null || echo -e "  ${DIM}- k6 없음 (부하 테스트에만 필요: brew install k6)${NC}"
command -v python3 > /dev/null || echo -e "  ${DIM}- python3 없음 (차트 백필에만 필요)${NC}"

echo ""
echo "Repo"
# .env는 선택이다 — 외부 API 키(KIS·Toss·AI 등)를 쓸 때만 필요하고, 없으면 전부 Mock으로 동작한다.
[ -f "$ROOT/.env" ] || echo -e "  ${DIM}- .env 없음 (외부 API 키를 쓸 때만 필요: cp .env.example .env)${NC}"
check "node_modules (pnpm install)"       test -d "$ROOT/node_modules"

echo ""
echo "Docker"
memory_check || failures=$((failures + 1))

echo ""
if [ "$failures" -gt 0 ]; then
  error "${failures}개 항목을 확인할 것"
  exit 1
fi
ok "준비됨 — scripts/dev/up.sh"
