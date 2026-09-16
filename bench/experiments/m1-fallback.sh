#!/usr/bin/env bash
# M-001(b) — Kafka 브로커 강제 종료 → 복구. WS 클라이언트 N개가 붙은 채로 docker kill → OUTAGE초 뒤 docker start.
# 이 저장소에는 "kafka → internal 자동 폴백"이 없다: ingestion.source 는 정적이고, internal 경로(MockPriceGenerator)도
# market.ticks 를 거친다(Stage 4) — Kafka 가 죽으면 모든 시세 경로가 함께 멈춘다. 그래서 여기서 재는 것은 전환 시간이 아니라
#   (1) 클라이언트가 보는 침묵 시간(관측 연결의 마지막 수신 → 재개), (2) 그동안 게이트웨이가 만든 틱 중 유실 수(seq gap),
#   (3) 복구 후 worker 컨슈머·api 브로드캐스트 컨슈머가 스스로 다시 붙는지, (4) WS 연결 자체는 유지되는지(api 는 살아 있다).
# 실행: bench/experiments/m1-fallback.sh   (N RUNS OUTAGE KILL_AT HOLD)
source "$(dirname "$0")/lib.sh"
N="${N:-1000}"; RUNS="${RUNS:-3}"; OUTAGE="${OUTAGE:-30}"; KILL_AT="${KILL_AT:-30}"; HOLD="${HOLD:-150}"
env_snapshot
SUM="$OUT/m1b-summary.tsv"
printf 'run\tkill_to_last_msg_s\tstart_to_resume_s\tsilence_s\tkafka_healthy_s\tworker_ticks\tworker_gaps\tgateway_publish_failed\tk6_conn_ok\tk6_silence_p50_ms\tk6_silence_p99_ms\tapi_broadcast_failed\n' > "$SUM"
API_PID=""; WORKER_PID=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$WORKER_PID"; stop_pid "$API_PID"' EXIT
counter() { curl -s "$1/actuator/prometheus" | awk -v k="$2" '$0 ~ "^"k {s+=$NF} END{print s+0}'; }
kafka_healthy() { [ "$(docker inspect monticker-kafka --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

recreate_ticks_topic 12
start_api SPRING_JPA_SHOW_SQL=false SEARCH_REINDEX_ON_STARTUP=false || exit 1
start_worker worker "$WORKER_PORT" KAFKA_CONSUMER_CONCURRENCY=4 || exit 1     # experiment 프로파일: seq gap = 유실
for r in $(seq 1 "$RUNS"); do
  tag="m1b-r$r"
  : > "$OUT/gateway.log"; start_gateway TICK_SEQ=true; sleep 5
  curl -s -X POST "$WORKER/experiment/tick-order/reset" >/dev/null
  node "$ROOT/bench/experiments/m1-observer.js" "ws://localhost:$API_PORT" "$HOLD" > "$OUT/$tag.observer" 2>&1 & OBS=$!
  k6 run -q --env N="$N" --env HOLD="$((HOLD-15))" --env RAMP=10 --env BASE_URL="$API" --summary-export "$OUT/$tag.json" "$ROOT/bench/experiments/m1-ws.js" > "$OUT/$tag.k6.txt" 2>&1 & K6=$!
  sleep "$KILL_AT"
  tkill=$(now_ms); docker kill monticker-kafka >/dev/null; log "  Kafka docker kill (+${KILL_AT}s)"
  sleep "$OUTAGE"
  tstart=$(now_ms); docker start monticker-kafka >/dev/null; log "  Kafka docker start (+$((KILL_AT+OUTAGE))s)"
  h0=$(date +%s); until kafka_healthy || [ $(( $(date +%s) - h0 )) -ge 120 ]; do sleep 2; done; healthy_s=$(( $(date +%s) - h0 ))
  log "  Kafka healthy +${healthy_s}s"
  wait "$K6" 2>/dev/null; wait "$OBS" 2>/dev/null
  stop_pid "$GW_PID"; GW_PID=""; sleep 5
  snap=$(curl -s "$WORKER/experiment/tick-order"); echo "$snap" > "$OUT/$tag.worker.json"
  python3 - "$OUT/$tag.observer" "$tkill" "$tstart" "$healthy_s" "$snap" "$(grep -c 'publish failed' "$OUT/gateway.log")" "$OUT/$tag.json" "$(counter "$API" tick_broadcast_failed_total)" "$r" >> "$SUM" <<'PY'
import sys,json
obs=[l.split() for l in open(sys.argv[1]) if l[:1].isdigit()]; tkill=int(sys.argv[2]); tstart=int(sys.argv[3])
recv=[int(o[0]) for o in obs]
last_before=max([t for t in recv if t<tkill+5000] or [tkill]); first_after=min([t for t in recv if t>tkill+5000] or [0])
s=json.loads(sys.argv[5]); k6=json.load(open(sys.argv[7]))["metrics"]
g=lambda n,k:(k6.get(n) or {}).get(k)
row=[sys.argv[9],round((last_before-tkill)/1000,1),round((first_after-tstart)/1000,1) if first_after else None,
     round((first_after-last_before)/1000,1) if first_after else None,sys.argv[4],s["ticks"],s["gaps"],sys.argv[6],
     g("ws_connect_ok","value"),g("ws_max_silence_ms","med"),g("ws_max_silence_ms","p(99)"),sys.argv[8]]
print("\t".join("" if x is None else str(x) for x in row))
PY
  log "  $tag: $(tail -1 "$SUM" | tr '\t' ' ')"
  wait_drain 60; sleep 5
done
log "완료 → $SUM"
