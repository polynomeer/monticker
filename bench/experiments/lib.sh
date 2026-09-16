#!/usr/bin/env bash
# 실험(reports/M-0xx) 공용 함수. 각 m1-*/m2-* 스크립트가 source 한다. bash 3.2(macOS 기본)에서 돈다.
#
# 전제: docker compose 로 postgres/redis/kafka 가 떠 있고(리소스 상한은 compose.limits.yml), api·worker jar 와
# Go market-gateway 바이너리가 빌드돼 있다. 모든 설정은 환경변수로만 바꾼다 — 코드 기본값은 건드리지 않는다.
#
# 환경변수(기본값은 로컬 대체 포트 스택):
#   PG_PORT=55432 REDIS_PORT=56379 KAFKA_BROKERS=localhost:29092
#   API_JAR / WORKER_JAR / GATEWAY  바이너리 경로     API_PORT=58080  WORKER_PORT=58081
#   OUT=<결과 디렉터리>  (raw JSON·로그가 여기 쌓인다. 보고서는 이걸 인용한다)
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PG_PORT="${PG_PORT:-55432}"; REDIS_PORT="${REDIS_PORT:-56379}"; KAFKA_BROKERS="${KAFKA_BROKERS:-localhost:29092}"
API_PORT="${API_PORT:-58080}"; WORKER_PORT="${WORKER_PORT:-58081}"
API_JAR="${API_JAR:-$ROOT/backend/api/build/libs/api-0.0.1-SNAPSHOT.jar}"
WORKER_JAR="${WORKER_JAR:-$ROOT/backend/worker/build/libs/worker-0.0.1-SNAPSHOT.jar}"
GATEWAY="${GATEWAY:-$ROOT/services/market-gateway/market-gateway}"
OUT="${OUT:-$ROOT/reports/_raw/$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
API="http://localhost:$API_PORT"; WORKER="http://localhost:$WORKER_PORT"
DB_JDBC="jdbc:postgresql://localhost:$PG_PORT/monticker"
DB_GO="postgres://monticker:monticker@localhost:$PG_PORT/monticker?sslmode=disable"
KT="docker exec monticker-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }

# ── 프로세스 ────────────────────────────────────────────────────────────────
# start_worker <이름> <포트> [KEY=VAL ...]  → pid 를 WORKER_PID 에. experiment 프로파일 + Go gateway 전용 소스.
start_worker() {
  local name=$1 port=$2; shift 2
  env DB_URL="$DB_JDBC" REDIS_PORT="$REDIS_PORT" KAFKA_BROKERS="$KAFKA_BROKERS" INGESTION_SOURCE=kafka \
      SPRING_PROFILES_ACTIVE=experiment SERVER_PORT="$port" "$@" \
      java -Xmx1g -jar "$WORKER_JAR" > "$OUT/$name.log" 2>&1 &
  WORKER_PID=$!
  wait_ready "http://localhost:$port" 120 || { log "worker $name 기동 실패"; tail -20 "$OUT/$name.log"; return 1; }
  log "worker $name 기동 pid=$WORKER_PID port=$port ($*)"
}
start_api() { # start_api [KEY=VAL ...] → API_PID
  env DB_URL="$DB_JDBC" REDIS_PORT="$REDIS_PORT" KAFKA_BROKERS="$KAFKA_BROKERS" SPRING_PROFILES_ACTIVE=local \
      SERVER_PORT="$API_PORT" "$@" java -Xmx2g -jar "$API_JAR" > "$OUT/api.log" 2>&1 &
  API_PID=$!
  wait_ready "$API" 180 || { log "api 기동 실패"; tail -20 "$OUT/api.log"; return 1; }
  log "api 기동 pid=$API_PID ($*)"
}
# start_gateway [KEY=VAL ...] → GW_PID
start_gateway() {
  env KAFKA_BROKERS="$KAFKA_BROKERS" DB_URL="$DB_GO" "$@" "$GATEWAY" >> "$OUT/gateway.log" 2>&1 &
  GW_PID=$!; log "gateway 기동 pid=$GW_PID ($*)"
}
# SIGTERM 뒤 최대 10s 기다리고 SIGKILL. 게이트웨이는 부하 중 SIGTERM 을 받으면 kafka-go Writer.Close 의 WaitGroup 에서 영원히
# 멈춘다(goroutine 덤프로 확인, D-M2-02) — 기다리기만 하면 실험 전체가 멈춘다.
stop_pid() {
  [ -n "${1:-}" ] || return 0
  kill "$1" 2>/dev/null || return 0
  local i=0; while kill -0 "$1" 2>/dev/null && [ $i -lt 20 ]; do sleep 0.5; i=$((i+1)); done
  if kill -0 "$1" 2>/dev/null; then kill -9 "$1" 2>/dev/null; log "  pid $1 SIGTERM 10s 무응답 → SIGKILL"; fi
  wait "$1" 2>/dev/null; return 0
}
wait_ready() { local base=$1 max=$2 i=0; while [ $i -lt "$max" ]; do
  curl -sf "$base/actuator/health/readiness" 2>/dev/null | grep -q '"UP"' && return 0; sleep 1; i=$((i+1)); done; return 1; }

# ── Kafka ────────────────────────────────────────────────────────────────────
# recreate_ticks_topic <파티션 수> — market.ticks 를 지우고 다시 만든다. 컨슈머(worker·api)가 없을 때만 부른다.
recreate_ticks_topic() {
  local p=$1
  $KT --delete --topic market.ticks >/dev/null 2>&1 || true
  local i=0; while $KT --list 2>/dev/null | grep -qx market.ticks; do sleep 1; i=$((i+1)); [ $i -gt 30 ] && break; done
  $KT --create --topic market.ticks --partitions "$p" --replication-factor 1 --config retention.ms=21600000 >/dev/null
  # api 의 KafkaTopicConfig(ADR-040)가 선언하는 나머지 토픽 전부. 하나라도 없으면 worker 가 그 토픽으로 보내는 발행(예: 감지
  # 이벤트 → search.index 아웃박스)이 매번 실패하며 스택트레이스를 찍고, 그 로깅이 측정을 오염시킨다(P1C1 에서 4분에 6만 줄).
  local existing; existing=$($KT --list 2>/dev/null)
  for t in market.ticks-retry-0 market.ticks-retry-1 market.ticks-dlt market.tick-processed market.tick-processed-retry-0 \
           market.tick-processed-retry-1 market.tick-processed-dlt notify.commands notify.commands-retry-0 notify.commands-retry-1 \
           notify.commands-dlt search.index search.index-dlt market.events market.summary trading.order-filled trading.order-cancelled; do
    echo "$existing" | grep -qx "$t" || $KT --create --topic "$t" --partitions 1 --replication-factor 1 >/dev/null
  done
  log "market.ticks 재생성: partitions=$p"
}
ticks_partitions() { $KT --describe --topic market.ticks 2>/dev/null | grep -c "Partition:"; }
# 컨슈머 그룹 랙 합계(monticker-worker)
group_lag() { docker exec monticker-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group monticker-worker 2>/dev/null | awk '$2=="market.ticks"{s+=$6} END{print s+0}'; }
# warmup <초> — worker 재기동 직후 첫 실행은 JIT 워밍업 전이라 p50이 10배 이상 높다(2026-09-16 P1C1: 835ms → 48ms).
# 조건마다 측정 전에 같은 부하를 잠깐 흘려 워밍업한다. 게이트웨이 인자는 호출자가 넘긴다.
warmup() { local secs=$1; shift; start_gateway "$@"; sleep "$secs"; stop_pid "$GW_PID"; GW_PID=""; wait_drain 60; log "  워밍업 ${secs}s 완료"; }
wait_drain() { local max=${1:-60} i=0; while [ $i -lt "$max" ]; do [ "$(group_lag)" = "0" ] && return 0; sleep 2; i=$((i+2)); done; return 1; }

# ── 관측 ─────────────────────────────────────────────────────────────────────
# metric <base> <이름> — actuator/metrics 의 첫 measurement
metric() { curl -s "$1/actuator/metrics/$2" | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["measurements"][0]["value"])
except Exception: print("nan")'; }
# sample_proc <base> <초> <파일> — HOLD 동안 5초마다 CPU(코어)·힙(MB)·스레드 를 기록하고 평균/최대를 남긴다(백그라운드)
sample_proc() {
  local base=$1 secs=$2 file=$3 n i=0
  n=$((secs/5))   # bash 3.2: 같은 local 문 안에서는 앞 변수를 참조할 수 없다
  : > "$file"
  while [ $i -lt $n ]; do sleep 5; i=$((i+1))
    printf '%s %s %s %s\n' "$(date +%s)" "$(metric "$base" process.cpu.usage)" "$(metric "$base" jvm.memory.used)" "$(metric "$base" jvm.threads.live)" >> "$file"
  done
}
proc_summary() { # proc_summary <파일> → JSON {cpu_cores_avg,cpu_cores_max,heap_mb_avg,heap_mb_max,threads_max}
  python3 - "$1" "$(sysctl -n hw.ncpu)" <<'PY'
import sys,json
rows=[l.split() for l in open(sys.argv[1]) if l.strip()]; cores=int(sys.argv[2])
def f(x):
    try: return float(x)
    except: return float('nan')
cpu=[f(r[1])*cores for r in rows]; heap=[f(r[2])/2**20 for r in rows]; thr=[f(r[3]) for r in rows]
import math
cpu=[c for c in cpu if not math.isnan(c)]; heap=[h for h in heap if not math.isnan(h)]; thr=[t for t in thr if not math.isnan(t)]
avg=lambda a: round(sum(a)/len(a),2) if a else None
print(json.dumps({"samples":len(rows),"cpu_cores_avg":avg(cpu),"cpu_cores_max":round(max(cpu),2) if cpu else None,
  "heap_mb_avg":avg(heap),"heap_mb_max":round(max(heap)) if heap else None,"threads_max":max(thr) if thr else None}))
PY
}
docker_stats() { docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' monticker-kafka monticker-redis monticker-postgres 2>/dev/null | tr '\n' ';'; }
env_snapshot() { # 보고서 "환경" 절 근거
  { echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "commit=$(git -C "$ROOT" rev-parse --short HEAD)"; echo "host=$(uname -m) $(sysctl -n hw.ncpu)cpu $(( $(sysctl -n hw.memsize)/2**30 ))GB"
    echo "java=$(java -version 2>&1 | head -1)"; echo "k6=$(k6 version 2>/dev/null | head -1)"; echo "go=$(go version 2>/dev/null)"
    docker version --format 'docker={{.Server.Version}}' 2>/dev/null; docker run --rm alpine sh -c 'free -m' 2>/dev/null | sed -n 2p | sed 's/^/vm_mem_mb: /'
    for c in monticker-kafka monticker-redis monticker-postgres; do docker inspect "$c" --format "$c: mem_limit={{.HostConfig.Memory}} cpus={{.HostConfig.NanoCpus}} image={{.Config.Image}}" 2>/dev/null; done
  } > "$OUT/env.txt"; cat "$OUT/env.txt"
}
