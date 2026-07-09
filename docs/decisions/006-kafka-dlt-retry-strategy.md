# ADR-006: Use @RetryableTopic and Dead Letter Topic for Kafka Consumer Fault Isolation

## Status
Accepted

## Context

`TickKafkaConsumer`는 `market.ticks` 토픽에서 시세 데이터를 소비한다.  
역직렬화 실패, DB 일시 장애, 외부 API 오류 등 일시적 예외가 발생했을 때 기본 Kafka 동작은 두 가지 중 하나다:

- 예외를 무시하고 다음 레코드로 진행 → 데이터 유실
- 예외를 던지고 파티션 블로킹 → 이후 모든 레코드 처리 중단

둘 다 허용할 수 없다. 일시적 오류는 재시도로 해결해야 하고, 재시도 후에도 실패한 레코드는 격리해 다른 레코드 처리를 보장해야 한다.

## Decision

Spring Kafka의 `@RetryableTopic` 어노테이션으로 자동 재시도 + Dead Letter Topic(DLT) 패턴을 적용한다.

```kotlin
@RetryableTopic(
    attempts = "3",
    backoff = Backoff(delay = 2_000, multiplier = 2.0),
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    dltTopicSuffix = "-dlt",
    autoCreateTopics = "false",
)
@KafkaListener(topics = ["market.ticks"], groupId = "monticker-worker")
fun onTick(record: ConsumerRecord<String, String>) { ... }

@DltHandler
fun onTickDlt(record: ConsumerRecord<String, String>) {
    log.error("[DLT] 최종 실패 — offset={}", record.offset())
}
```

재시도 토픽은 인프라 코드로 사전 생성(`autoCreateTopics = "false"`).

## Reasons

- `@RetryableTopic`은 재시도를 별도 토픽(`-retry-0`, `-retry-1`)으로 분리해 원본 파티션 블로킹을 방지한다.
- 지수 백오프(2초 → 4초)는 일시적 DB 지연에 적합하며, 최대 블로킹 시간을 6초로 제한한다.
- DLT 격리로 독성 레코드(poison pill)가 파이프라인을 영구 중단시키는 것을 방지한다.
- `@DltHandler`는 최소한 로그 경보를 남겨 운영팀이 수동 개입할 수 있는 신호를 제공한다.
- 코드 변경 없이 annotation 하나로 설정되므로 컨슈머 로직의 복잡도를 올리지 않는다.

## Consequences

- 재시도 토픽(`market.ticks-retry-0`, `market.ticks-retry-1`, `market.ticks-dlt`)을 사전 생성해야 한다.
- 재시도 간격 동안 최대 6초의 처리 지연이 발생할 수 있다 (시세 데이터는 허용 범위).
- DLT 레코드에 대한 알림·재처리 파이프라인은 아직 없다. 모니터링 성숙 시 별도 DLT 컨슈머 그룹을 추가한다.

## Revisit When

- DLT 레코드가 빈번해져 자동 재처리 로직이 필요해질 때.
- 시세 데이터가 아닌 금융 트랜잭션 이벤트에 Kafka 소비가 추가될 때 (더 엄격한 보장 필요).
