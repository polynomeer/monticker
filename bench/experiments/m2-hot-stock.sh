#!/usr/bin/env bash
# M-002(c) — 핫 종목 head-of-line blocking. 한 종목(기본 stockId=2)만 폭주시키고, 그 종목·같은 파티션의 종목·
# 나머지 종목의 e2e 지연을 나눠 본다. 키=stockId 라 핫 종목은 한 파티션에 고정되고, 그 파티션을 맡은 리스너 스레드
# 하나가 폭주를 전부 처리한다 — 같은 파티션에 매핑된 다른 종목이 같이 밀리는지가 질문이다.
#
# 조건(CASES): control(핫 없음) | hot(핫 종목 HOT_INTERVAL ms 간격) | hot-slow(핫 + 틱당 SLOW_MS 처리 지연 주입)
# 부하: 나머지 201종목 100ms 간격(≈2,010/s) + 핫 종목 HOT_INTERVAL(기본 2ms = 500/s).
# 실행: bench/experiments/m2-hot-stock.sh   (P C HOT_STOCK HOT_INTERVAL SLOW_MS RUNS HOLD)
source "$(dirname "$0")/lib.sh"
P="${P:-6}"; C="${C:-6}"; HOT_STOCK="${HOT_STOCK:-2}"; HOT_INTERVAL="${HOT_INTERVAL:-2}"; SLOW_MS="${SLOW_MS:-2}"
RUNS="${RUNS:-3}"; HOLD="${HOLD:-40}"; CASES="${CASES:-control hot hot-slow}"
env_snapshot
SUM="$OUT/m2c-summary.tsv"
printf 'case\trun\tticks\ttick_s\tviolations\tdups\tgaps\thot_partition\thot_n\thot_p50\thot_p95\thot_p99\tsame_n\tsame_p50\tsame_p95\tsame_p99\tother_n\tother_p50\tother_p95\tother_p99\tworker_cpu_cores\n' > "$SUM"
WORKER_PID=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$WORKER_PID"' EXIT
for case in $CASES; do
  stop_pid "$WORKER_PID"; WORKER_PID=""
  recreate_ticks_topic "$P"
  slow=0; [ "$case" = hot-slow ] && slow=$SLOW_MS
  start_worker "worker-$case" "$WORKER_PORT" KAFKA_CONSUMER_CONCURRENCY="$C" EXP_HOT_STOCK_ID="$HOT_STOCK" EXP_SLOW_STOCK_ID="$HOT_STOCK" EXP_SLOW_MS="$slow" || exit 1
  sleep 5
  warmup "${WARMUP:-20}" TICK_INTERVAL_MS=100 TICK_SEQ=true
  for r in $(seq 1 "$RUNS"); do
    tag="m2c-$case-r$r"
    curl -s -X POST "$WORKER/experiment/tick-order/reset" >/dev/null
    sample_proc "$WORKER" "$HOLD" "$OUT/$tag.proc" & SP=$!
    if [ "$case" = control ]; then start_gateway TICK_INTERVAL_MS=100 TICK_SEQ=true
    else start_gateway TICK_INTERVAL_MS=100 TICK_SEQ=true TICK_HOT_STOCK_ID="$HOT_STOCK" TICK_HOT_INTERVAL_MS="$HOT_INTERVAL"; fi
    sleep "$HOLD"; stop_pid "$GW_PID"; GW_PID=""; wait "$SP" 2>/dev/null
    wait_drain 90 || log "  경고: 90s 내 랙 미소진 (lag=$(group_lag))"
    curl -s "$WORKER/experiment/tick-order" > "$OUT/$tag.json"
    python3 - "$OUT/$tag.json" "$(proc_summary "$OUT/$tag.proc")" "$HOLD" "$case" "$r" >> "$SUM" <<'PY'
import sys,json
s=json.load(open(sys.argv[1])); pr=json.loads(sys.argv[2]); hold=int(sys.argv[3])
def c(k):
    e=s["e2e"].get(k) or {}; return [e.get("n",0),e.get("p50_ms"),e.get("p95_ms"),e.get("p99_ms")]
print("\t".join(str(x) for x in [sys.argv[4],sys.argv[5],s["ticks"],round(s["ticks"]/hold),s["violations"],s["dups"],s["gaps"],s["hot_partition"]]+c("hot")+c("same_partition_as_hot")+c("other")+[pr["cpu_cores_avg"]]))
PY
    log "  $tag: $(tail -1 "$SUM" | cut -f3-20 | tr '\t' ' ')"
  done
done
log "완료 → $SUM"
