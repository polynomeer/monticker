#!/usr/bin/env bash
# 로컬 개발 스택 상태: 인프라 컨테이너, 앱 헬스, Docker 메모리.
. "$(dirname "$0")/lib.sh"
load_state

echo "mode: ${MODE}$([ -f "$STATE_FILE" ] || echo '  (up.sh 기록 없음 — 기본 포트로 확인)')"
echo ""
echo "Infra"
if docker_running; then
  rows=$(compose_all ps -a --format '{{.Service}}	{{.State}}	{{.Status}}' 2>/dev/null)
  if [ -z "$rows" ]; then
    echo -e "  ${DIM}컨테이너 없음${NC}"
  else
    while IFS=$'\t' read -r svc state status; do
      case "$status" in
        *unhealthy*|Exited*) color=$RED ;;
        *healthy*|Up*)       color=$GREEN ;;
        *)                   color=$YELLOW ;;
      esac
      oom=""
      if [ "$state" = "exited" ]; then
        cid=$(compose_all ps -aq "$svc" 2>/dev/null | head -1)
        [ "$(docker inspect "$cid" --format '{{.State.OOMKilled}}' 2>/dev/null)" = "true" ] && oom="  ← 메모리 부족(OOM)으로 종료됨"
      fi
      printf "  %-20s ${color}%s${NC}%s\n" "$svc" "$status" "$oom"
    done <<< "$rows"
  fi
else
  echo -e "  ${RED}Docker가 꺼져 있다${NC}"
fi

echo ""
echo "Apps"
app_status() {
  local name="$1" port="$2" health_path="$3" expect="$4" pid body
  if [ -z "$port" ]; then
    printf "  %-8s ${DIM}%s${NC}\n" "$name" "(이 모드에서는 띄우지 않음)"; return
  fi
  pid=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null | head -1)
  if [ -z "$pid" ]; then
    printf "  %-8s ${RED}DOWN${NC}  :%s\n" "$name" "$port"; return
  fi
  if ! repo_listener_pid "$port" > /dev/null; then
    printf "  %-8s ${YELLOW}:%s 를 이 저장소가 아닌 프로세스가 쓰고 있다 (pid %s)${NC}\n" "$name" "$port" "$pid"; return
  fi
  # 기본 포트로 추측한 경우 다른 앱(예: 8081을 쥔 api)을 잘못 가리킬 수 있다 — 커맨드라인으로 확인한다.
  if [ -n "$expect" ] && ! ps -p "$pid" -o command= 2>/dev/null | grep -qF "$expect"; then
    printf "  %-8s ${RED}DOWN${NC}  (:%s 는 다른 앱이 쓰고 있다)\n" "$name" "$port"; return
  fi
  if [ -z "$health_path" ]; then
    printf "  %-8s ${GREEN}UP${NC}    :%s  pid %s\n" "$name" "$port" "$pid"; return
  fi
  body=$(curl -s -m 3 "http://localhost:${port}${health_path}")
  case "$body" in
    *'"status":"UP"'*)
      printf "  %-8s ${GREEN}UP${NC}    :%s  pid %s\n" "$name" "$port" "$pid" ;;
    *)
      # 전체 health가 DOWN이어도 readiness가 UP이면 서빙은 된다 — 어느 쪽인지 같이 보여준다.
      local ready
      ready=$(curl -s -m 3 "http://localhost:${port}${health_path}/readiness")
      printf "  %-8s ${YELLOW}%s${NC}  :%s  pid %s  (readiness: %s)\n" "$name" "${body:-응답 없음}" "$port" "$pid" "${ready:-응답 없음}" ;;
  esac
}
app_status api "$API_PORT" /actuator/health "${APP_JVM_PATTERNS[0]}"
if [ "$MODE" = msa ]; then
  printf "  %-8s ${DIM}%s${NC}\n" worker "컨테이너(worker-market/event/alert) — 위 Infra 참고"
else
  app_status worker "$WORKER_PORT" /actuator/health "${APP_JVM_PATTERNS[1]}"
fi
app_status web "$WEB_PORT" ""

if docker_running; then
  echo ""
  echo "Docker memory"
  "$ROOT/scripts/dev/doctor.sh" --memory-only --verbose
fi
exit 0
