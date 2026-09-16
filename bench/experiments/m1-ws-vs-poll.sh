#!/usr/bin/env bash
# M-001(a) — WS push vs REST polling, 클라이언트 N ∈ N_LIST. 같은 틱 스트림(게이트웨이 202종목 × 1 tick/s, 기본 간격)을
# 두고 (1) STOMP/WS N연결, (2) 폴링 1s, (3) 폴링 500ms 를 각각 RUNS 회. 관측: 클라이언트 지연(k6), api 프로세스
# CPU(코어)·힙·스레드(actuator 샘플링), api 카운터(ws_broadcast_messages_out_total, http.server.requests) 증분.
# api 는 이 스크립트가 띄운다(SPRING_PROFILES_ACTIVE=local, show-sql·reindex 끔). worker 는 기본 프로파일로 같이 띄운다
# (Redis 최신가·캔들 — 폴링 엔드포인트가 Redis 를 읽는다).
# 실행: bench/experiments/m1-ws-vs-poll.sh   (N_LIST MODES RUNS HOLD SUBS)
source "$(dirname "$0")/lib.sh"
N_LIST="${N_LIST:-1000 5000 10000}"; MODES="${MODES:-ws poll1000 poll500}"; RUNS="${RUNS:-3}"; HOLD="${HOLD:-60}"; SUBS="${SUBS:-5}"
env_snapshot
SUM="$OUT/m1a-summary.tsv"
printf 'mode\tN\trun\tconn_ok\tstomp_ok\tmsgs\tmsg_s\tp50_ms\tp95_ms\tp99_ms\tmax_ms\thttp_reqs\treq_s\thttp_fail\tdropped\thttp_p99_ms\tapi_cpu_cores\tapi_heap_mb\tapi_threads\tws_out_delta\n' > "$SUM"
API_PID=""; WORKER_PID=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$WORKER_PID"; stop_pid "$API_PID"' EXIT
counter() { curl -s "$API/actuator/prometheus" | awk -v k="$1" '$0 ~ "^"k {s+=$NF} END{print s+0}'; }

recreate_ticks_topic 12
start_api SPRING_JPA_SHOW_SQL=false SEARCH_REINDEX_ON_STARTUP=false || exit 1
env DB_URL="$DB_JDBC" REDIS_PORT="$REDIS_PORT" KAFKA_BROKERS="$KAFKA_BROKERS" INGESTION_SOURCE=kafka SERVER_PORT="$WORKER_PORT" \
    java -Xmx1g -jar "$WORKER_JAR" > "$OUT/worker.log" 2>&1 & WORKER_PID=$!
wait_ready "$WORKER" 120 || { log "worker 기동 실패"; exit 1; }
start_gateway            # 기본: 1 tick/s/종목 ≈ 202 tick/s, seq 없음, 키=stockId
sleep 5
# 워밍업: WS 200연결 20s + 폴링 1,000 req/s 20s
k6 run -q --env N=200 --env HOLD=15 --env SUBS="$SUBS" --env BASE_URL="$API" "$ROOT/bench/experiments/m1-ws.js" > /dev/null 2>&1
k6 run -q --env N=200 --env INTERVAL_MS=1000 --env HOLD=15 --env SUBS="$SUBS" --env BASE_URL="$API" "$ROOT/bench/experiments/m1-poll.js" > /dev/null 2>&1
log "워밍업 완료"

for n in $N_LIST; do for mode in $MODES; do for r in $(seq 1 "$RUNS"); do
  tag="m1a-$mode-N$n-r$r"
  ws0=$(counter ws_broadcast_messages_out_total)
  case $mode in
    ws)   ramp=$(( n/200 > 10 ? n/200 : 10 )); dur=$((HOLD+ramp))
          sample_proc "$API" "$dur" "$OUT/$tag.proc" & SP=$!
          k6 run -q --env N="$n" --env HOLD="$HOLD" --env SUBS="$SUBS" --env BASE_URL="$API" --summary-export "$OUT/$tag.json" \
             "$ROOT/bench/experiments/m1-ws.js" > "$OUT/$tag.k6.txt" 2>&1 ;;
    poll*) iv=${mode#poll}
          sample_proc "$API" "$HOLD" "$OUT/$tag.proc" & SP=$!
          k6 run -q --env N="$n" --env INTERVAL_MS="$iv" --env HOLD="$HOLD" --env SUBS="$SUBS" --env BASE_URL="$API" --summary-export "$OUT/$tag.json" \
             "$ROOT/bench/experiments/m1-poll.js" > "$OUT/$tag.k6.txt" 2>&1 ;;
  esac
  wait "$SP" 2>/dev/null; ws1=$(counter ws_broadcast_messages_out_total)
  python3 - "$OUT/$tag.json" "$(proc_summary "$OUT/$tag.proc")" "$mode" "$n" "$r" "$HOLD" "$((ws1-ws0))" >> "$SUM" <<'PY'
import sys,json
j=json.load(open(sys.argv[1])); m=j["metrics"]; pr=json.loads(sys.argv[2]); mode,n,r,hold,wsd=sys.argv[3:8]
def g(name,key):
    v=m.get(name,{}); return v.get(key)
def t(name):
    v=m.get(name,{}); return [v.get("med"),v.get("p(95)"),v.get("p(99)"),v.get("max")]
row=[mode,n,r]
if mode=="ws":
    row+= [g("ws_connect_ok","value"),g("ws_stomp_connected","value"),g("ws_messages","count"),round((g("ws_messages","count") or 0)/int(hold))]+t("ws_tick_to_client_ms")+[None,None,None,None,None]
else:
    row+= [None,None,None,None]+t("poll_staleness_ms")+[g("http_reqs","count"),g("http_reqs","rate"),g("http_req_failed","value"),g("dropped_iterations","count"),g("http_req_duration","p(99)")]
row+=[pr["cpu_cores_avg"],pr["heap_mb_max"],pr["threads_max"],wsd]
print("\t".join("" if x is None else (str(round(x,1)) if isinstance(x,float) else str(x)) for x in row))
PY
  log "  $tag: $(tail -1 "$SUM" | cut -f4-20 | tr '\t' ' ')"
  sleep 10
done; done; done
log "완료 → $SUM"
