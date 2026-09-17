#!/usr/bin/env bash
# M-002 §4.4 후속 — 실시간 파이프라인 p95 꼬리 스파이크 원인 규명. 최적 조건(P6·C6)에서 긴 단일 실행을 하며
# (1) e2e 임계 초과 틱의 벽시계 시각·종목·파티션·분내 위치(worker TickOrderMonitor slow_ticks),
# (2) worker GC 로그(-Xlog:gc), (3) 캔들 flush 카운터를 모아 스파이크가 무엇과 정렬되는지 본다.
# 실행: bench/experiments/m2-tail.sh   (P C DURATION THRESH RUNS)
source "$(dirname "$0")/lib.sh"
P="${P:-6}"; C="${C:-6}"; DURATION="${DURATION:-180}"; THRESH="${THRESH:-500}"; RUNS="${RUNS:-2}"
env_snapshot
WORKER_PID=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$WORKER_PID"' EXIT
recreate_ticks_topic "$P"
for r in $(seq 1 "$RUNS"); do
  tag="tail-r$r"; gclog="$OUT/$tag.gc.log"
  stop_pid "$WORKER_PID"; WORKER_PID=""
  # GC 로그를 켠 worker (JAVA_TOOL_OPTIONS 로 주입 — start_worker 를 안 건드린다)
  JAVA_TOOL_OPTIONS="-Xlog:gc*,safepoint:file=$gclog:time,uptime,level,tags" \
    start_worker "worker-$tag" "$WORKER_PORT" KAFKA_CONSUMER_CONCURRENCY="$C" EXP_SLOW_TICK_THRESHOLD_MS="$THRESH" || exit 1
  sleep 5
  warmup "${WARMUP:-25}" TICK_INTERVAL_MS=100 TICK_SEQ=true
  curl -s -X POST "$WORKER/experiment/tick-order/reset" >/dev/null

  # 브로커/DB 자원 1초 샘플링(클래스 B 규명 — 스파이크 순간 브로커 CPU 가 2코어 한도에 붙는지 본다)
  ( end=$(( $(date +%s) + DURATION + 5 ))
    while [ "$(date +%s)" -lt "$end" ]; do
      ts=$(python3 -c 'import time;print(int(time.time()*1000))')
      docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' monticker-kafka monticker-postgres monticker-redis 2>/dev/null         | sed "s/^/$ts /" >> "$OUT/$tag.dockerstats"
    done ) & SAMP=$!
  start_gateway TICK_INTERVAL_MS=100 TICK_SEQ=true
  log "  r$r: ${DURATION}s 관측 (P=$P C=$C thresh=${THRESH}ms)"
  sleep "$DURATION"
  kill "$SAMP" 2>/dev/null
  stop_pid "$GW_PID"; GW_PID=""
  wait_drain 60
  curl -s "$WORKER/experiment/tick-order" > "$OUT/$tag.json"
  curl -s "$WORKER/actuator/prometheus" | grep -E '^jvm_gc_pause_seconds|^candle_flush' > "$OUT/$tag.metrics" 2>/dev/null
  n=$(python3 -c "import json;d=json.load(open('$OUT/$tag.json'));print(len(d.get('slow_ticks',[])))")
  log "  r$r: slow_ticks=$n → $OUT/$tag.json, gc=$gclog"
done
log "완료 → $OUT"
