#!/usr/bin/env bash
# CH-09 Kafka 파티션 리밸런스 반복 (resilience-plan §6.2)
#
# 가설: 틱이 흐르는 동안 컨슈머(worker)를 30초 간격으로 5분간 재시작해도 (1) 그룹이 매번 재합류해 랙이
#   소진되고 (2) 커밋 오프셋이 토픽 끝에 도달해 유실 0이며 (3) DLT로 빠지는 메시지가 없다.
#   graceful(SIGTERM, server.shutdown=graceful)로 죽인다 — K8s의 기본 종료 방식. 캔들 유실은 기록만.
# 관측: 사이클마다 종료 시간·기동 시간·랙 소진 시간, 끝에 consumer-groups --describe LAG 합, DLT 오프셋.
# 실행: GATEWAY=<binary> WORKER_JAR=<jar> WORKER=http://localhost:58081 KAFKA_BROKERS=localhost:29092 …env… bench/chaos/ch09-rebalance-storm.sh
# 중단: 재시작 후 readiness 120s 내 미복구, 랙이 사이클마다 누적 증가.
set -u
source "$(dirname "$0")/lib.sh"
GATEWAY="${GATEWAY:?market-gateway 바이너리}"; WORKER_JAR="${WORKER_JAR:?worker jar}"; WORKER="${WORKER:-http://localhost:8081}"
INTERVAL=${INTERVAL:-30}; CYCLES=${CYCLES:-10}; TICK_MS=${TICK_MS:-200}; WLOG=${WLOG:-/tmp/ch09-worker}
KT=/opt/kafka/bin
wready() { curl -s -o /dev/null -w '%{http_code}' "$WORKER/actuator/health/readiness"; }
wpid() { pgrep -f 'worker-0.0.1-SNAPSHOT.jar' | head -1; }
lag() { curl -s "$WORKER/actuator/prometheus" | grep '^kafka_consumer_fetch_manager_records_lag_max' | grep 'topic="market_ticks"' | awk '{if($NF+0>m)m=$NF+0} END{print m+0}'; }
group_lag() { docker exec monticker-kafka $KT/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "$1" 2>/dev/null | awk 'NR>1 && $1=="'$1'" {s+=$6} END{print s+0}'; }
end_offsets() { docker exec monticker-kafka $KT/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$1" 2>/dev/null | awk -F: '{s+=$3} END{print s+0}'; }
start_worker() { (nohup java -jar "$WORKER_JAR" > "$WLOG-$1.log" 2>&1 &); local s=$(date +%s); until [ "$(wready)" = "200" ] || [ $(( $(date +%s) - s )) -ge 120 ]; do sleep 1; done; echo $(( $(date +%s) - s )); }

echo "== 0. 시작 상태: worker pid=$(wpid) readiness=$(wready)  DLT 오프셋 ticks-dlt=$(end_offsets market.ticks-dlt) tick-processed-dlt=$(end_offsets market.tick-processed-dlt)"
DLT0=$(( $(end_offsets market.ticks-dlt) + $(end_offsets market.tick-processed-dlt) ))
T0=$(end_offsets market.ticks)
echo "== 1. 틱 스톰 시작 (TICK_INTERVAL_MS=$TICK_MS)"; TICK_INTERVAL_MS=$TICK_MS "$GATEWAY" > /tmp/ch09-gw.log 2>&1 & GW=$!
sleep 15; echo "  15s 후 랙=$(lag) 생산=$(( $(end_offsets market.ticks) - T0 ))"
printf "%-6s %-10s %-10s %-10s %-12s %-10s\n" cycle stop_s start_s lag_at_up drain_s lag_after
echo "== 2. 재시작 $CYCLES회 × ${INTERVAL}s"
for c in $(seq 1 $CYCLES); do
  P=$(wpid); s=$(date +%s); kill -TERM "$P"; while kill -0 "$P" 2>/dev/null && [ $(( $(date +%s) - s )) -lt 30 ]; do sleep 0.5; done
  if kill -0 "$P" 2>/dev/null; then kill -9 "$P"; STOPK="+SIGKILL"; else STOPK=""; fi
  stop_n=$(( $(date +%s) - s )); stop_s="$stop_n$STOPK"
  # 랙은 브로커 쪽(consumer-groups LAG)으로 본다 — 워커 메트릭은 그룹 재합류 전엔 아예 없어서 0으로 읽힌다
  start_s=$(start_worker "$c"); lag_up=$(group_lag monticker-worker)
  s=$(date +%s); until [ "$(group_lag monticker-worker)" -le 100 ] || [ $(( $(date +%s) - s )) -ge $INTERVAL ]; do sleep 1; done; drain=$(( $(date +%s) - s ))
  printf "%-6s %-10s %-10s %-10s %-12s %-10s\n" "$c" "$stop_s" "$start_s" "$lag_up" "$drain" "$(group_lag monticker-worker)"
  rem=$(( INTERVAL - drain - start_s - stop_n )); [ $rem -gt 0 ] && sleep $rem
done
echo "== 3. 스톰 종료, 최종 소진 대기"; kill $GW 2>/dev/null; wait $GW 2>/dev/null
s=$(date +%s); until [ "$(group_lag monticker-worker)" = "0" ] || [ $(( $(date +%s) - s )) -ge 90 ]; do sleep 3; done
echo "  생산 틱=$(( $(end_offsets market.ticks) - T0 ))  그룹 LAG: worker=$(group_lag monticker-worker) alert-worker=$(group_lag monticker-alert-worker) ($(( $(date +%s) - s ))s)"
DLT1=$(( $(end_offsets market.ticks-dlt) + $(end_offsets market.tick-processed-dlt) ))
echo "  DLT 증가=$(( DLT1 - DLT0 ))  candle_flush_failed=$(curl -s $WORKER/actuator/prometheus | awk '/^candle_flush_failed_total/{print $NF; exit}')"
[ "$(group_lag monticker-worker)" = "0" ] && [ $(( DLT1 - DLT0 )) -eq 0 ] && echo "  PASS — 유실 0, DLT 0" || echo "  FAIL"
