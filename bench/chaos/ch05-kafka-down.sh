#!/usr/bin/env bash
# CH-05 Kafka 브로커 정지 (resilience-plan §6.2) — ADR-008 Outbox의 첫 실증
#
# 가설: 브로커가 죽어도 주문은 DB 커밋으로 성공하고(HTTP 200, 지연 무영향), 외부화 이벤트
#   (trading.order-filled)는 event_publication에 미완료로 남았다가 브로커 복구 후 5분 내
#   (OutboxResubmissionConfig 주기) 자동 재전송돼 미완료 0으로 수렴한다. readiness는 내내 UP
#   (Kafka는 readiness 그룹에 없다 — 시세 브로드캐스트만 죽지 주문은 살아야 한다).
# 관측: event_publication 외부화 행의 completion_date IS NULL 수, outbox_pending_total, JVM 스레드 수
#   (producer max.block.ms 기본 60s — 비동기 externalizer 스레드가 매달리면 스레드가 불어난다).
# 중단: 주문이 503/500이 되거나 readiness DOWN, 또는 복구 후 7분 내 미수렴.
set -u
source "$(dirname "$0")/lib.sh"
PSQL="docker exec monticker-postgres psql -U monticker -d monticker -tA -c"
TOK=$(fresh_token)
STOCK=$($PSQL "SELECT stock_id FROM candles_1m ORDER BY candle_time DESC LIMIT 1")
incomplete() { $PSQL "SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND listener_id LIKE '%Externalizer%'"; }
# 실제로 브로커에 도달했는지 — 토픽 끝 오프셋 합 (미완료 0이 "발행 성공"인지 "완료 표시만"인지 가른다)
delivered() { docker exec monticker-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic trading.order-filled 2>/dev/null | awk -F: '{s+=$3} END {print s+0}'; }
kafka_healthy() { [ "$(docker inspect monticker-kafka --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
threads() { curl -s "$API/actuator/metrics/jvm.threads.live" | python3 -c 'import sys,json; print(int(json.load(sys.stdin)["measurements"][0]["value"]))'; }
morder() { probe "$1" -X POST "$API/api/matching/orders" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -H "X-Idempotency-Key: ch05-$RANDOM-$RANDOM" -d "{\"stockId\":$STOCK,\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":1}"; }

echo "== 1. 정상: 외부화 미완료=$(incomplete) 브로커 도달=$(delivered) threads=$(threads)"
morder "POST /api/matching/orders"; sleep 3; echo "  외부화 미완료(3s 후)=$(incomplete) 브로커 도달=$(delivered)"
echo "== 2. 주입: docker compose stop kafka"; compose stop kafka >/dev/null; sleep 3
echo "== 3. 관측 (Kafka 없음) — 주문 5건"
for i in 1 2 3 4 5; do morder "POST /api/matching/orders #$i"; done
probe "GET readiness" "$API/actuator/health/readiness"
for s in 5 30 65; do sleep $(( s == 5 ? 5 : 25 )); echo "  +${s}s 외부화 미완료=$(incomplete) threads=$(threads)"; done
metrics outbox_pending_total
echo "== 4. 복구: docker compose start kafka"; compose start kafka >/dev/null
start=$(date +%s); until kafka_healthy || [ $(( $(date +%s) - start )) -ge 120 ]; do sleep 3; done
echo "  브로커 healthy ($(( $(date +%s) - start ))s) — 도달=$(delivered)"
echo "== 5. 수렴 폴링 — 재전송 주기 5분 + 1분 유예 (최대 7분)"; start=$(date +%s)
while :; do n=$(incomplete); el=$(( $(date +%s) - start ))
  echo "  +${el}s 외부화 미완료=$n 브로커 도달=$(delivered) threads=$(threads)"
  [ "$n" = "0" ] && { echo "  PASS — 미완료 0 수렴, 복구 후 ${el}s, 브로커 도달=$(delivered)"; break; }
  [ $el -ge 420 ] && { echo "  FAIL — 7분 내 미수렴 ($n)"; break; }; sleep 20; done
morder "POST /api/matching/orders (복구 후)"; sleep 3; echo "  외부화 미완료=$(incomplete) 브로커 도달=$(delivered)"
