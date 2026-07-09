# ADR-008: Outbox Pattern via Spring Modulith Events Kafka

## Status
Accepted

## Context

`MatchingService.submitOrder()`는 주문 체결 후 `OrderFilledEvent`를 발행한다.  
이 이벤트를 Kafka로 직접 발행하면 원자성 문제가 발생한다:

- DB 커밋 전 Kafka 발행 → DB 롤백 시 이미 발행된 이벤트를 철회할 수 없다
- DB 커밋 후 Kafka 발행 → 발행 시점에 프로세스가 죽으면 이벤트 유실

두 경우 모두 컨슈머가 존재하지 않는 주문에 대한 이벤트를 받거나, 반대로 이벤트를 전혀 받지 못하는 상황을 만든다.

ADR-001(모듈러 모놀리스)에서 이미 Spring Modulith를 도입해 `event_publication` 인프라가 갖춰져 있다.

## Decision

Spring Modulith의 `spring-modulith-events-kafka` 모듈로 Outbox Pattern을 구현한다.  
이벤트에 `@Externalized`를 붙이면 Spring Modulith가 Outbox 역할을 수행한다.

```kotlin
@Externalized("trading.order-filled::#{#this.userId}")
data class OrderFilledEvent(...)

@Externalized("trading.order-cancelled::#{#this.userId}")
data class OrderCancelledEvent(...)
```

- 토픽: `"trading.order-filled"`, `"trading.order-cancelled"`
- 파티션 키: `userId` — 동일 사용자의 이벤트 순서 보장

발행 흐름:
1. `publishEvent()` 호출 → `event_publication` 테이블에 INSERT (동일 트랜잭션)
2. COMMIT
3. Spring Modulith가 Kafka 발행 → `completion_date` UPDATE

추가로 `OutboxResubmissionConfig`가 5분마다 1분 이상 미완료 레코드를 재처리한다.

Kafka Producer는 `enable.idempotence: true`, `acks: all`로 설정해 브로커 레벨 중복도 방지한다.

## Reasons

- **`@Externalized` 어노테이션 하나**로 Outbox Pattern 전체를 적용할 수 있다. 별도 아웃박스 테이블 설계, 폴링 스케줄러 작성이 불필요하다.
- `event_publication` 테이블은 ADR-001에서 선택한 Spring Modulith가 이미 관리한다 — 새로운 인프라 의존성이 추가되지 않는다.
- `"::#{#this.userId}"` SpEL 파티션 키로 동일 사용자의 이벤트 순서를 Kafka 파티션 수준에서 보장한다.
- At-least-once 보장: 재전송 시 컨슈머가 멱등성을 확보하면 중복 처리 문제도 해결된다.

## Consequences

- **At-least-once**: Kafka 발행 성공 후 `completion_date` 업데이트 전 크래시가 나면 같은 이벤트가 재발행된다. 컨슈머는 멱등성을 가정해야 한다.
- `event_publication` 테이블이 커질 수 있다. 완료된 레코드의 TTL 또는 주기적 정리가 필요하다.
- Kafka가 다운되면 `event_publication`의 미완료 레코드가 쌓인다. 재처리 스케줄러가 Kafka 복구 후 자동으로 처리한다.
- 인-프로세스 `@ApplicationModuleListener`는 여전히 실행된다. Outbox를 통한 Kafka 발행과 인-프로세스 리스너는 독립적이다.

## Revisit When

- 이벤트 소비 컨슈머가 외부 서비스로 분리되어 정확히 한 번(exactly-once) 처리가 필요해질 때 → Kafka Transactions 또는 Transactional Outbox + Saga 조합 검토.
- `event_publication` 레코드 누적이 문제가 될 때 → 완료 레코드 자동 정리(TTL 또는 batch delete) 추가.
