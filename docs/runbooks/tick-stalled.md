# 장중 시세 정지 — `TickPipelineStalled` (page) · `DltMessagesGrowing` · `CandleFlushFailing`

## 증상
- 장중(KST 09:00~15:30) 3분간 worker 처리 틱 0 **또는** `market.ticks` 컨슈머 랙 > 60,000.
- 사용자: 시세가 멈춰 보인다(화면은 마지막 값 유지), 알림 미발동, 캔들 갱신 없음.

## 파이프라인 (어디서 멈췄나를 순서대로 좁힌다)
```
market-gateway(Go) ─► Kafka market.ticks ─► worker(TickKafkaConsumer, 동시성 4) ─► Redis·캔들·이벤트·알림
                                         └► api(AllPartitionsListener, 수동 할당) ─► STOMP fan-out
```
Realtime Pipeline 대시보드의 패널이 이 순서다.

## 1차 확인 (3단계)
1. **유입이 있는가**: Kafka 토픽 끝 오프셋이 늘고 있는지.
   `kafka-get-offsets.sh --topic market.ticks` 두 번(10초 간격). 안 늘면 **게이트웨이/증권사 소스** 문제 → 게이트웨이 pod 로그, KIS/Toss 실시간 세션(ADR-030/031, 등록 한도).
2. **worker가 읽는가**: 대시보드 "틱 처리량"이 0인데 랙이 늘면 **worker**. `kafka-consumer-groups.sh --describe --group monticker-worker` — 멤버가 있는가, 리밸런스 중인가(CH-09: 재시작 후 재합류 6~13초는 정상).
3. **api fan-out**: worker는 처리하는데 화면이 멈추면 **api 브로드캐스트**. `ws_broadcast_ticks_in_total` 증가 여부, `tick_broadcast_failed_total`. api는 수동 파티션 할당(ADR-038)이라 그룹이 없다 — 파티션 수가 바뀐 뒤 재시작 안 했으면 새 파티션을 모른다.

## 완화
- **게이트웨이**: 재시작. KIS 실시간 등록 한도(41건/커넥션)에 걸렸으면 종목 수 확인.
- **worker**: 랙만 쌓이고 처리가 0이면 재시작(SIGTERM, graceful 30초). CH-09 실측: 재기동 6~13초, 랙 수백~수천 건은 수 초에 소진. **재시작 중 캔들 1분봉 일부 유실**(§E4, 인메모리 집계) — 기록만 한다.
- **Kafka**: 브로커 down이면 CH-05 경로 — 주문은 Outbox로 안전, 시세만 끊긴다. 브로커 복구가 우선.
- **파티션 증설 직후**라면 api 재시작(브로드캐스트 리스너가 파티션 목록을 기동 시 읽는다).

## DLT가 늘 때 (`DltMessagesGrowing`)
- `kafka-console-consumer --topic market.ticks-dlt --from-beginning --property print.headers=true`에서 `kafka_dlt-exception-message` 헤더. 역직렬화 실패(스키마 변경)인지 처리 실패(Redis/DB)인지.
- 처리 실패면 원인 해결 후 오프셋 되감기로 재처리 가능(틱은 멱등). 역직렬화 실패면 코드 수정 — 데이터는 버린다(틱은 재생산된다).

## 근본 원인 조사
- worker 스레드 덤프(처리 0 + CPU 높음 = 어딘가 매달림; ADR-046 이전엔 Redis 동기 대기가 그랬다).
- `tick_pipeline_slow_total` 급증 = 처리는 되나 느림 → 다운스트림(Redis 지연 → [redis-down.md](redis-down.md), DB → [db-failover.md](db-failover.md)).

## 복구 후 검증
- 랙 0 수렴(`consumer-groups LAG`), `tick_latency_total_pipeline_seconds` p99 < 300ms, 화면 시세 갱신, `candle_flush_failed_total` 평탄.

## 에스컬레이션
- 장중 10분 이상 정지 → 인시던트. 15분 이상이면 사용자 공지(시세 지연 안내).
