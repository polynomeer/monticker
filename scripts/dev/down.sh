#!/usr/bin/env bash
# 로컬 개발 스택 정리. up.sh를 Ctrl-C로 끄지 못했을 때(터미널을 닫았거나 up.sh가 실패한 경우) 남은 프로세스까지 정리한다.
set -e
. "$(dirname "$0")/lib.sh"

usage() {
  cat <<EOF
Usage: scripts/dev/down.sh [options]

  (없음)         앱(api·worker·web)을 끄고 인프라 컨테이너를 멈춘다(데이터는 남는다)
  --apps-only    앱만 끄고 인프라는 그대로 둔다 — 다음 up.sh가 빨리 뜬다
  --volumes      인프라 컨테이너와 볼륨까지 삭제한다(DB·ES 데이터가 지워진다, 확인을 묻는다)
  -h, --help     이 도움말
EOF
}

APPS_ONLY=false
VOLUMES=false
for arg in "$@"; do
  case "$arg" in
    --apps-only) APPS_ONLY=true ;;
    --volumes)   VOLUMES=true ;;
    -h|--help)   usage; exit 0 ;;
    *)           echo "알 수 없는 옵션: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

load_state
echo "Stopping apps..."
stopped=0
for p in "${APP_JVM_PATTERNS[@]}"; do
  if pkill -f "$p" 2>/dev/null; then stopped=$((stopped + 1)); fi
done
for port in "$API_PORT" "$WORKER_PORT" "$WEB_PORT"; do
  [ -z "$port" ] && continue
  if pid=$(repo_listener_pid "$port"); then
    kill "$pid" 2>/dev/null && stopped=$((stopped + 1))
  fi
done
# next dev는 부모(node .../next dev)와 실제 서버(next-server)가 따로 뜬다 — 포트로 서버를 죽인 뒤 남은 부모도 정리한다.
pkill -f "$ROOT/node_modules/.*next dev" 2>/dev/null || true
if [ "$stopped" -gt 0 ]; then ok "앱 프로세스 정리됨"; else echo -e "  ${DIM}떠 있는 앱 없음${NC}"; fi

if [ "$APPS_ONLY" = true ]; then
  echo "인프라는 그대로 둔다 (scripts/dev/down.sh 로 전부 정리)"
  exit 0
fi

if ! docker_running; then
  warn "Docker가 꺼져 있다 — 인프라 정리는 건너뜀"
  rm -f "$STATE_FILE"
  exit 0
fi

if [ "$VOLUMES" = true ]; then
  echo -e "${YELLOW}로컬 DB·Redis·Mongo·ES·Kafka 볼륨을 삭제합니다. 되돌릴 수 없습니다.${NC}"
  read -r -p "계속하려면 'yes' 입력: " answer
  [ "$answer" = "yes" ] || { echo "취소"; exit 1; }
  compose_all down --volumes --remove-orphans
else
  echo "Stopping infra..."
  compose_all stop
fi
rm -f "$STATE_FILE"
ok "Done."
