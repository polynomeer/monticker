# Outbox Pattern — Spring Modulith Events Kafka

## 배경

`OrderFilledEvent`가 Spring `ApplicationEventPublisher`로 발행될 때,  
Kafka 메시지 발행과 DB 트랜잭션 사이에 **원자성이 없다**:

- DB 커밋 성공 → Kafka 발행 실패: 다운스트림 컨슈머가 이벤트를 수신 못 함
- Kafka 발행 성공 → DB 커밋 실패: 존재하지 않는 주문에 대한 이벤트가 전파됨

**Outbox Pattern**은 이벤트를 DB에 먼저 쓰고, 그 후 별도 프로세스가 Kafka에 발행해 원자성을 확보한다.

---

## 구현 — Spring Modulith Events

Spring Modulith의 `spring-modulith-events-kafka`는 Outbox Pattern을 기본 제공한다.

### 의존성

```kotlin
// backend/api/build.gradle.kts
implementation("org.springframework.modulith:spring-modulith-events-kafka")
```

### 이벤트 라우팅 설정

```kotlin
// OrderFilledEvent.kt
@Externalized("trading.order-filled::#{#this.userId}")
data class OrderFilledEvent(
    val orderId: Long,
    val userId: Long,
    ...
)

// OrderCancelledEvent.kt
@Externalized("trading.order-cancelled::#{#this.userId}")
data class OrderCancelledEvent(...)
```

- `"trading.order-filled"` — Kafka 토픽 이름
- `"::#{#this.userId}"` — 메시지 파티션 키 (SpEL 표현식, 동일 userId 순서 보장)

### 발행 흐름

```
submitOrder() 트랜잭션
  │
  ├─ orders INSERT
  ├─ fills INSERT
  ├─ paper_accounts UPDATE
  │
  ├─ publishEvent(OrderFilledEvent)
  │      │
  │      └─► Spring Modulith가 event_publication 테이블에 레코드 INSERT
  │          (completion_date = NULL)
  │
  └─ COMMIT
       │
       └─► EventPublicationRegistry가 Kafka에 발행
            └─ 성공 → completion_date = now() UPDATE
```

DB 커밋이 실패하면 `event_publication`도 롤백되어 Kafka에 발행되지 않는다.  
Kafka 발행이 실패하면 `completion_date`가 NULL로 남아 재처리 대상이 된다.

---

## event_publication 테이블

```sql
-- V18__create_spring_modulith_event_publication.sql
CREATE TABLE event_publication (
    id               UUID        PRIMARY KEY,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ           -- NULL = 미완료
);
```

---

## 재처리 — OutboxResubmissionConfig

Spring Modulith는 기동 시 미완료 이벤트를 자동 재처리한다.  
추가로 주기적 재처리 스케줄러를 구성해 **1분 이상 미완료인 레코드**를 능동적으로 재시도한다.

```kotlin
// OutboxResubmissionConfig.kt
@Component
class OutboxResubmissionConfig(private val incompletePublications: IncompleteEventPublications) {

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)  // 5분마다
    fun resubmit() {
        incompletePublications.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1))
    }
}
```

### 재처리 보장 수준

**At-least-once**: Kafka 발행 후 DB 업데이트 전에 장애가 나면 중복 발행 가능.  
컨슈머는 멱등성을 확보해야 한다(중복 이벤트 수신 시 부작용 없음).

---

## Kafka Producer 설정

```yaml
# application.yml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all          # 모든 파티션 복제본 확인
      retries: 3
      properties:
        enable.idempotence: true   # 프로듀서 멱등성 (중복 발행 방지)
```

`enable.idempotence: true`는 Kafka 브로커 레벨에서 중복 메시지를 제거한다 (sequence number 기반).

---

## 토픽 설계

| 토픽 | 파티션 키 | 소비자 그룹 | 용도 |
|------|-----------|-------------|------|
| `trading.order-filled` | `userId` | (확장 예정) | 체결 알림, 포트폴리오 동기화 |
| `trading.order-cancelled` | `userId` | (확장 예정) | 취소 알림, 잔고 복원 알림 |

userId를 파티션 키로 사용하면 동일 사용자의 이벤트가 동일 파티션에 순서대로 기록된다.

---

## 인-프로세스 리스너와의 관계

```
publishEvent(OrderFilledEvent)
  │
  ├─► event_publication INSERT (Outbox, DB → Kafka)
  │
  └─► @ApplicationModuleListener 인-프로세스 리스너도 동시에 실행
       (OrderFilledEventListener, OrderFilledStrategyListener)
```

외부 Kafka 발행(Outbox)과 인-프로세스 리스너는 **독립적**이다.  
인-프로세스 리스너는 같은 트랜잭션에서 즉시 실행되고,  
Kafka 발행은 커밋 후 별도로 처리된다.

---

## 관련 문서

- [eda-event-driven-architecture.md](./eda-event-driven-architecture.md) — 인-프로세스 이벤트 패턴
- [kafka-tick-pipeline.md](./kafka-tick-pipeline.md) — 시세 Kafka 파이프라인
- [resilience-patterns.md](./resilience-patterns.md) — DLT·재시도 패턴
