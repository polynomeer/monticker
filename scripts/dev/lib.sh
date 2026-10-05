# scripts/dev/*.sh 공용. source 해서 쓴다 — 직접 실행하지 않는다.
#
# 로컬 개발 스택은 두 층이다: docker compose 인프라(postgres·redis·mongodb·elasticsearch·jaeger·mailhog, 선택 kafka 등)와
# 호스트에서 도는 앱(api·worker는 gradlew bootRun, web은 next dev). up.sh가 실제로 쓴 포트를 STATE_FILE에 남기고,
# down.sh·status.sh는 그 파일을 읽는다 — 포트가 충돌해 8080 대신 8082로 떴어도 정확히 그 프로세스를 찾는다.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT/logs"
STATE_FILE="$LOG_DIR/.dev-state"

if [ -t 1 ]; then
  RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; DIM='\033[2m'; NC='\033[0m'
else
  RED=''; YELLOW=''; GREEN=''; CYAN=''; DIM=''; NC=''
fi

info()  { echo -e "  ${CYAN}$*${NC}"; }
ok()    { echo -e "  ${GREEN}$*${NC}"; }
warn()  { echo -e "  ${YELLOW}[WARN] $*${NC}" >&2; }
error() { echo -e "${RED}[FAILED] $*${NC}" >&2; }

# 모든 프로파일을 켠 compose — stop/ps가 프로파일 서비스(kafka, worker-* 등)까지 보게 한다.
compose_all() { docker compose --project-directory "$ROOT" --profile full --profile kafka --profile msa --profile pinpoint "$@"; }

docker_running() { docker info > /dev/null 2>&1; }

# bootRun JVM은 Gradle 데몬이 띄워서 gradlew를 죽여도 남는다. 커맨드라인에 빌드 산출물 경로가 들어 있어 이걸로 찾는다.
APP_JVM_PATTERNS=("$ROOT/backend/api/build" "$ROOT/backend/worker/build")

# 포트를 리슨 중인 프로세스 중 작업 디렉터리가 이 저장소 안인 것만 고른다 — 다른 프로젝트의 프로세스는 절대 건드리지 않는다.
repo_listener_pid() {
  local port="$1" pid cwd
  for pid in $(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null); do
    cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')
    case "$cwd" in "$ROOT"|"$ROOT"/*) echo "$pid"; return 0 ;; esac
    ps -p "$pid" -o command= 2>/dev/null | grep -qF "$ROOT" && { echo "$pid"; return 0; }
  done
  return 1
}

# 커맨드라인이 패턴과 맞는 프로세스가 리슨하는 포트. bootRun JVM은 관리 포트 등 여럿을 열 수 있어 가장 작은 것을 고른다.
discover_port() {
  local pid
  for pid in $(pgrep -f "$1" 2>/dev/null); do
    lsof -Pan -p "$pid" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 { sub(/.*:/, "", $9); print $9 }'
  done | sort -n | head -1
}

# STATE_FILE 읽기. 없으면(up.sh 밖에서 띄웠거나 이전 버전이 남긴 프로세스) 실제 프로세스에서 포트를 찾고, 그래도 없으면 기본 포트.
load_state() {
  API_PORT=8080; WORKER_PORT=8081; WEB_PORT=3000; MODE=default
  if [ -f "$STATE_FILE" ]; then
    # shellcheck disable=SC1090
    . "$STATE_FILE"
  else
    local p
    p=$(discover_port "${APP_JVM_PATTERNS[0]}"); [ -n "$p" ] && API_PORT=$p
    p=$(discover_port "${APP_JVM_PATTERNS[1]}"); [ -n "$p" ] && WORKER_PORT=$p
  fi
  return 0
}
