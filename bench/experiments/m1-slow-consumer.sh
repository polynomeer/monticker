#!/usr/bin/env bash
# M-001(c) — 느린 소비자/백프레셔. 정상 클라이언트 N개(k6 m1-ws.js)가 붙어 있는 동안 SLOW개의 느린 연결이 202종목×REPEAT
# 구독을 걸고 읽기를 멈춘다. 관측: 느린 연결이 서버에 의해 끊기기까지(netstat 상태가 CLOSE_WAIT 로 바뀌는 시각), 그동안
# 정상 클라이언트의 지연·최대 침묵(k6), api CPU·힙·스레드, ws_broadcast_messages_out_total.
# SLOW 를 clientOutboundChannel 스레드 수(기본 2×코어 = 이 머신 20)보다 크게도 잡아 본다 — 풀 고갈 여부.
# 실행: bench/experiments/m1-slow-consumer.sh   (SLOW_LIST="1 25" REPEAT N RUNS HOLD)
source "$(dirname "$0")/lib.sh"
SLOW_LIST="${SLOW_LIST:-1 25}"; REPEAT="${REPEAT:-5}"; N="${N:-1000}"; RUNS="${RUNS:-3}"; HOLD="${HOLD:-60}"; SLOW_AT="${SLOW_AT:-15}"
env_snapshot
SUM="$OUT/m1c-summary.tsv"
printf 'slow\trun\tslow_closed\tslow_close_s_min\tslow_close_s_max\tnormal_p50_ms\tnormal_p95_ms\tnormal_p99_ms\tnormal_max_silence_p99_ms\tnormal_msgs\tapi_cpu_cores\tapi_heap_mb\tapi_threads\tws_out_delta\n' > "$SUM"
API_PID=""; WORKER_PID=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$WORKER_PID"; stop_pid "$API_PID"' EXIT
counter() { curl -s "$API/actuator/prometheus" | awk -v k="$1" '$0 ~ "^"k {s+=$NF} END{print s+0}'; }
recreate_ticks_topic 12
start_api SPRING_JPA_SHOW_SQL=false SEARCH_REINDEX_ON_STARTUP=false || exit 1
env DB_URL="$DB_JDBC" REDIS_PORT="$REDIS_PORT" KAFKA_BROKERS="$KAFKA_BROKERS" INGESTION_SOURCE=kafka SERVER_PORT="$WORKER_PORT" \
    java -Xmx1g -jar "$WORKER_JAR" > "$OUT/worker.log" 2>&1 & WORKER_PID=$!
wait_ready "$WORKER" 120 || exit 1
start_gateway; sleep 5
k6 run -q --env N=200 --env HOLD=15 --env BASE_URL="$API" "$ROOT/bench/experiments/m1-ws.js" > /dev/null 2>&1; log "워밍업 완료"

for slow in $SLOW_LIST; do for r in $(seq 1 "$RUNS"); do
  tag="m1c-S$slow-r$r"; ws0=$(counter ws_broadcast_messages_out_total)
  sample_proc "$API" "$((HOLD+10))" "$OUT/$tag.proc" & SP=$!
  k6 run -q --env N="$N" --env HOLD="$HOLD" --env RAMP=10 --env BASE_URL="$API" --summary-export "$OUT/$tag.json" "$ROOT/bench/experiments/m1-ws.js" > "$OUT/$tag.k6.txt" 2>&1 & K6=$!
  sleep "$SLOW_AT"
  t0=$(date +%s)
  node "$ROOT/bench/experiments/m1-slow-consumer.js" localhost "$API_PORT" "$slow" "$REPEAT" "$((HOLD-SLOW_AT))" > "$OUT/$tag.slow.json" 2> "$OUT/$tag.slow.err" & SLOWP=$!
  sleep 3; ports=$(python3 -c 'import sys,json; print(" ".join(str(p) for p in json.load(open(sys.argv[1]))["ports"]))' "$OUT/$tag.slow.json" 2>/dev/null)
  log "  느린 연결 $slow개 (구독 $((202*REPEAT))개씩) 포트: $(echo $ports | cut -c1-60)"
  : > "$OUT/$tag.close"
  # 각 느린 연결의 TCP 상태를 1초마다 — 서버가 세션을 닫으면 클라이언트 쪽이 CLOSE_WAIT 가 된다(우리는 읽지 않으므로 close 를 못 본다)
  while kill -0 "$SLOWP" 2>/dev/null; do
    for p in $ports; do
      grep -q "^$p " "$OUT/$tag.close" && continue
      st=$(netstat -an -p tcp 2>/dev/null | awk -v p=".$p " '$4 ~ p"$" {print $6; exit}')
      case "$st" in ESTABLISHED) ;; *) echo "$p $(( $(date +%s) - t0 )) ${st:-gone}" >> "$OUT/$tag.close";; esac
    done; sleep 1
  done
  wait "$K6" 2>/dev/null; wait "$SP" 2>/dev/null; ws1=$(counter ws_broadcast_messages_out_total)
  python3 - "$OUT/$tag.json" "$(proc_summary "$OUT/$tag.proc")" "$OUT/$tag.close" "$slow" "$r" "$((ws1-ws0))" >> "$SUM" <<'PY'
import sys,json
j=json.load(open(sys.argv[1]))["metrics"]; pr=json.loads(sys.argv[2]); closes=[l.split() for l in open(sys.argv[3]) if l.strip()]
secs=[int(c[1]) for c in closes]
t=lambda n,k: (j.get(n) or {}).get(k)
row=[sys.argv[4],sys.argv[5],len(closes),min(secs) if secs else None,max(secs) if secs else None,
     t("ws_tick_to_client_ms","med"),t("ws_tick_to_client_ms","p(95)"),t("ws_tick_to_client_ms","p(99)"),t("ws_max_silence_ms","p(99)"),t("ws_messages","count"),
     pr["cpu_cores_avg"],pr["heap_mb_max"],pr["threads_max"],sys.argv[6]]
print("\t".join("" if x is None else (str(round(x,1)) if isinstance(x,float) else str(x)) for x in row))
PY
  log "  $tag: $(tail -1 "$SUM" | cut -f3-14 | tr '\t' ' ')"; sleep 10
done; done
log "완료 → $SUM"
