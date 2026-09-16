#!/usr/bin/env bash
# M-002(b) — 리밸런스 중 순서·중복·유실. worker 프로세스 2개(같은 컨슈머 그룹, 각 C 스레드)가 P 파티션을 나눠 갖는
# 상태에서 하나를 SIGKILL 하고, 잠시 뒤 다시 띄운다. 두 프로세스가 관측한 모든 틱은 Redis 스트림
# experiment:tick-order 에 남으므로(EXP_REDIS_LOG=true) 죽은 프로세스의 관측도 잃지 않는다.
# 분석은 m2-analyze-stream.py — 종목별로 스트림 도착 순서대로 seq 를 훑어 위반/중복/유실과 파티션 인계 공백을 센다.
#
# 타임라인(초): 0 게이트웨이 시작 → KILL_AT w2 SIGKILL → RESTART_AT w2 재기동 → HOLD 게이트웨이 정지 → 랙 소진 → 분석
# 실행: bench/experiments/m2-rebalance.sh   (P C RUNS HOLD KILL_AT RESTART_AT)
source "$(dirname "$0")/lib.sh"
P="${P:-6}"; C="${C:-3}"; RUNS="${RUNS:-3}"; HOLD="${HOLD:-75}"; KILL_AT="${KILL_AT:-25}"; RESTART_AT="${RESTART_AT:-45}"
W2_PORT=$((WORKER_PORT+1))
env_snapshot
SUM="$OUT/m2b-summary.tsv"
printf 'run\tticks_logged\tunique\texpected\tlost\tdups\tviolations\tstocks_affected\tw2_partitions\thandover_gap_ms\tw2_rejoin_s\tw1_e2e_p99_ms_during\n' > "$SUM"
W1=""; W2=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$W1"; stop_pid "$W2"' EXIT
redis() { docker exec monticker-redis redis-cli "$@"; }

recreate_ticks_topic "$P"
for r in $(seq 1 "$RUNS"); do
  tag="m2b-r$r"
  stop_pid "$W1"; stop_pid "$W2"; W1=""; W2=""
  start_worker "w1-r$r" "$WORKER_PORT" KAFKA_CONSUMER_CONCURRENCY="$C" EXP_REDIS_LOG=true EXP_WORKER_ID=w1 || exit 1; W1=$WORKER_PID
  start_worker "w2-r$r" "$W2_PORT"     KAFKA_CONSUMER_CONCURRENCY="$C" EXP_REDIS_LOG=true EXP_WORKER_ID=w2 || exit 1; W2=$WORKER_PID
  sleep 8   # 두 멤버 모두 가입 후 파티션 분배 안정화
  warmup "${WARMUP:-20}" TICK_INTERVAL_MS=100 TICK_SEQ=true
  redis DEL experiment:tick-order >/dev/null
  t0=$(python3 -c 'import time;print(int(time.time()*1000))')   # macOS date 는 %3N 을 모른다
  start_gateway TICK_INTERVAL_MS=100 TICK_SEQ=true
  sleep "$KILL_AT"
  tkill=$(python3 -c 'import time;print(int(time.time()*1000))'); kill -9 "$W2"; wait "$W2" 2>/dev/null; W2=""
  log "  w2 SIGKILL at +${KILL_AT}s"
  sleep $((RESTART_AT-KILL_AT))
  trestart=$(python3 -c 'import time;print(int(time.time()*1000))')
  start_worker "w2b-r$r" "$W2_PORT" KAFKA_CONSUMER_CONCURRENCY="$C" EXP_REDIS_LOG=true EXP_WORKER_ID=w2 || exit 1; W2=$WORKER_PID
  now=$(python3 -c 'import time;print(int(time.time()*1000))'); remain=$(( HOLD*1000 - (now - t0) )); [ $remain -gt 0 ] && sleep $((remain/1000))
  stop_pid "$GW_PID"; GW_PID=""
  wait_drain 60 || log "  경고: 랙 미소진 lag=$(group_lag)"
  sleep 2
  # 게이트웨이가 종목마다 실제로 발행한 seq 수 = 기대치. 스트림에 없는 seq 가 유실이다.
  redis --raw XRANGE experiment:tick-order - + > "$OUT/$tag.stream"
  python3 "$(dirname "$0")/m2-analyze-stream.py" "$OUT/$tag.stream" "$tkill" "$trestart" > "$OUT/$tag.json"
  python3 - "$OUT/$tag.json" "$r" >> "$SUM" <<'PY'
import sys,json; a=json.load(open(sys.argv[1]))
print("\t".join(str(a.get(k)) for k in ["run","ticks_logged","unique","expected","lost","dups","violations","stocks_affected","w2_partitions","handover_gap_ms","w2_rejoin_s","w1_e2e_p99_ms_during"]).replace("None",sys.argv[2],1))
PY
  log "  $tag: $(tail -1 "$SUM" | tr '\t' ' ')"
done
log "완료 → $SUM"
