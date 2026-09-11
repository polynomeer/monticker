#!/usr/bin/env bash
# L-03 tick-storm — resilience-plan §5.1 / ADR-045
#
# Go market-gateway(TICK_INTERVAL_MS)로 틱 유입률을 단계적으로 올리며 워커 파이프라인이 따라오는지 본다.
# k6로는 Kafka 프로듀서 부하를 만들 수 없다 — 도구를 나눈 이유(ADR-045 §2).
#
# 측정 (워커 /actuator/prometheus):
#   kafka_consumer_fetch_manager_records_lag_max   — 컨슈머 랙 (SLO < 5s 상당. 파티션 1개인 현재 구조의 상한이 여기서 드러난다)
#   tick_latency_total_pipeline_seconds{0.99}      — 틱 생성 → 파이프라인 처리 완료 p99
#   candle_flush_failed_total                      — 정합성 (부하 중 캔들 유실)
# 실행: GATEWAY=<binary> WORKER=http://localhost:58081 KAFKA_BROKERS=localhost:29092 DB_URL=postgres://... bench/scenarios/tick-storm.sh
set -u
GATEWAY="${GATEWAY:?market-gateway 바이너리 경로}"; WORKER="${WORKER:-http://localhost:8081}"
STAGES="${STAGES:-1000 100 20}"      # TICK_INTERVAL_MS 단계: 202종목 기준 ~200 / ~2,000 / ~10,000 tick/s
HOLD="${HOLD:-60}"; SAMPLE="${SAMPLE:-10}"
metric() { curl -s "$WORKER/actuator/prometheus" | awk -v k="$1" '$0 ~ "^"k {print $NF; exit}'; }
# 컨슈머가 여럿(worker, notify, retry…)이라 첫 줄이 아니라 market.ticks 파티션들의 최대 랙을 쓴다
lag()    { curl -s "$WORKER/actuator/prometheus" | grep '^kafka_consumer_fetch_manager_records_lag_max' | grep 'topic="market.ticks"' | awk '{if($NF+0>m)m=$NF+0} END{print m+0}'; }
q99()    { curl -s "$WORKER/actuator/prometheus" | grep '^tick_latency_total_pipeline_seconds{' | grep 'quantile="0.99"' | awk '{print $NF}'; }
printf "%-8s %-8s %-10s %-12s %-10s %-8s\n" stage sec lag_max p99_pipe_ms ticks_s flushFail
for ms in $STAGES; do
  TICK_INTERVAL_MS=$ms "$GATEWAY" > /tmp/gw-$ms.log 2>&1 & GW=$!
  c0=$(metric 'tick_latency_total_pipeline_seconds_count'); t0=$(date +%s)
  for ((t=SAMPLE; t<=HOLD; t+=SAMPLE)); do
    sleep "$SAMPLE"
    c1=$(metric 'tick_latency_total_pipeline_seconds_count'); t1=$(date +%s)
    rate=$(echo "scale=0; (${c1:-0} - ${c0:-0}) / ($t1 - $t0)" | bc 2>/dev/null)
    printf "%-8s %-8s %-10s %-12s %-10s %-8s\n" "${ms}ms" "$t" "$(lag)" \
      "$(echo "$(q99) * 1000" | bc 2>/dev/null | cut -d. -f1)" "$rate" "$(metric 'candle_flush_failed_total')"
    c0=$c1; t0=$t1
  done
  kill $GW 2>/dev/null; wait $GW 2>/dev/null
  echo "  -- ${ms}ms 단계 종료, 랙 소진 대기 20s"; sleep 20
  printf "%-8s %-8s %-10s\n" "drain" "" "$(lag)"
done
