# ADR-040: Kafka 토픽을 코드로 선언 — auto-create 폐지와 파티션 설계

## Status
Accepted

## Context

`docker-compose.yml`의 Kafka 브로커는 `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`이고,
`KAFKA_CFG_NUM_PARTITIONS`(또는 `KAFKA_NUM_PARTITIONS`)를 설정하지 않는다.
저장소 전체에 `NewTopic` 빈이 **하나도 없다**(`grep -rn "NewTopic" backend` → 0건).

결과:

1. **모든 토픽이 파티션 1개로 자동 생성된다** (브로커 기본값 `num.partitions=1`).
   `market.ticks`의 컨슈머 병렬성이 구조적으로 1로 고정된다 —
   `ConcurrentKafkaListenerContainerFactory`의 concurrency를 아무리 올려도 파티션이 1개면
   활성 컨슈머는 1개다. 나머지 스레드는 idle 상태로 대기한다.
   scale-out-plan §1의 T1(3,000 tick/s)에서 이미 랙이 쌓이기 시작한다.

2. **파티션 수·복제 계수·retention을 아무도 소유하지 않는다.** 토픽이 "처음 접근하는
   프로듀서/컨슈머에 의해 우연히" 생성되므로, 어떤 설정으로 만들어졌는지 코드나 IaC를
   봐서는 알 수 없다. 운영 환경에서 잘못된 설정으로 생성되면 파티션은 줄일 수 없고
   retention 변경은 이미 유실된 데이터를 되돌리지 못한다.

3. **숨은 결합이 있다.** 세 개의 `@RetryableTopic` 컨슈머
   ([`TickKafkaConsumer`](../../backend/worker/src/main/kotlin/com/monticker/worker/kafka/TickKafkaConsumer.kt#L69),
   [`AlertKafkaConsumer`](../../backend/worker/src/main/kotlin/com/monticker/worker/kafka/AlertKafkaConsumer.kt#L28),
   [`OrderFilledKafkaConsumer`](../../backend/quant-engine/src/main/kotlin/com/monticker/api/quant/application/OrderFilledKafkaConsumer.kt#L28))가
   전부 `autoCreateTopics = "false"`로 설정돼 있다. 즉 재시도·DLT 토픽 생성을
   Spring이 아니라 **브로커의 auto-create에 떠넘기고 있다.**
   auto-create를 그냥 끄면 [ADR-006](006-kafka-dlt-retry-strategy.md)의 재시도/DLT 전략이
   통째로 조용히 망가진다. 이 두 변경은 반드시 함께 가야 한다.

또한 현재 브로커는 **단일 노드**다(`KAFKA_NODE_ID: 1`, KRaft,
`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`). 복제 계수 3은 브로커를 늘리기 전에는
설정할 수 없다.

**선행 조건**: 파티션을 늘리면 [scale-out-plan §3.1](../scale-out-plan.md)의 브로드캐스트
버그가 더 넓게 퍼진다(지금은 파티션이 1개라 "한 pod만 전부 받는" 형태로 나타난다).
**[ADR-038](038-broadcast-consumer-partition-assignment.md)이 먼저 배포돼야 한다.**

## Decision

### 1. 브로커 auto-create를 끈다

```yaml
# docker-compose.yml
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

### 2. 토픽을 `NewTopic` 빈으로 선언한다

`backend/api`에 `KafkaTopicConfig`를 두고 `KafkaAdmin`이 기동 시 생성/검증하게 한다.
파티션 수·복제 계수는 **환경변수로 주입**해 로컬과 운영이 같은 코드로 다른 값을 쓴다.

```kotlin
@Configuration
class KafkaTopicConfig(private val props: KafkaTopicProperties) {

    @Bean fun marketTicks() = topic("market.ticks", props.marketTicks, retentionMs = 6.hours)
    @Bean fun marketSummary() = topic("market.summary", partitions = 1, retentionMs = 1.hours)   // ADR-039
    @Bean fun marketEvents() = topic("market.events", props.marketEvents, retentionMs = 7.days)
    @Bean fun tickProcessed() = topic("market.tick-processed", props.tickProcessed, retentionMs = 1.hours)
    @Bean fun orderFilled() = topic("trading.order-filled", props.trading, retentionMs = 30.days,
                                    minInSyncReplicas = 2)
    @Bean fun orderCancelled() = topic("trading.order-cancelled", props.trading, retentionMs = 30.days,
                                       minInSyncReplicas = 2)

    // @RetryableTopic이 만드는 토픽도 여기서 함께 선언한다 (§Consequences)
    @Bean fun retryTopics() = listOf(
        retryFamily("market.ticks", attempts = 3),
        retryFamily("market.tick-processed", attempts = 3),
        retryFamily("trading.order-filled", attempts = 4),
    ).flatten()
}
```

### 3. 파티션·복제·보존 정책

| 토픽 | 키 | 로컬/dev | prod (T1) | **T2 목표** | 복제 | retention | 비고 |
|------|----|---------|----------|-----------|------|-----------|------|
| `market.ticks` | stockId | 12 | 64 | **256** | 1 → 3 | 6h | 최대 처리량, 짧은 보존 |
| `market.tick-processed` | stockId | 6 | 32 | 128 | 1 → 3 | 1h | 알림 파이프라인 |
| `market.events` | stockId | 3 | 16 | 32 | 1 → 3 | 7d | 이벤트 감지 결과 |
| `market.summary` | — | 1 | 1 | 1 | 1 → 3 | 1h | 1 msg/s (ADR-039) |
| `trading.order-*` | userId | 3 | 16 | 64 | 1 → **3** | **30d** | 유실 불가, `min.insync.replicas=2` |
| `*-retry-N` / `*-dlt` | 원본 유지 | 1 | 4 | 4 | 1 → 3 | 30d | 수동 재처리 |

- **복제 계수는 브로커 수에 종속된다.** 단일 브로커인 동안은 전부 1이고, 멀티 브로커
  전환(Phase 1) 시 3으로 올린다. `trading.*`은 **멀티 브로커 없이는 유실 위험이 남는다** —
  현재 `acks=all` + `enable.idempotence=true` 설정([ADR-008](008-outbox-pattern-spring-modulith.md))은
  복제 계수가 1이면 "ISR 1개의 확인"일 뿐이다. 이 사실을 Consequences에 남긴다.
- **파티션 수 산정 근거**: T2 피크 150,000 msg/s ÷ 파티션당 안전 처리량 ~2,000 msg/s
  (엔드투엔드 처리 포함) ≈ 75 → 성장 여유 3배로 256. 512는 넘기지 않는다
  (브로커 메타데이터·리밸런스 비용).

### 4. 파티션 증설 절차

파티션을 늘리면 `hash(key) % partitionCount`가 바뀌어 **같은 종목이 다른 파티션으로
이동한다.** 순서 보장이 그 경계에서 한 번 깨진다.

```
1. 장 마감 후(KRX/US 양쪽 마감 시간대) 수행한다.
2. kafka-topics --alter --partitions N   (증가만 가능, 감소 불가)
3. worker를 재기동한다 — CandleAggregator의 인메모리 캔들 상태(§ADR-042 예정)가
   파티션 이동으로 갈라지므로, 진행 중이던 1분봉 1개가 유실된다. 마감 후이므로 영향 없음.
4. backend/api를 재기동한다 — 브로드캐스트 컨슈머는 기동 시 파티션 수를 읽어
   전 파티션을 할당하므로(ADR-038), 재기동 전까지 새 파티션을 잡지 않는다.
```

무중단이 필요하면 신규 토픽(`market.ticks.v2`)을 만들고 프로듀서/컨슈머를 순차 전환하는
방법이 있으나, 장 마감 창이 매일 존재하는 이 도메인에서는 과한 복잡도다.

## Reasons

- **파티션 1개는 튜닝이 아니라 상한이다.** 워커 concurrency, pod 수, 인스턴스 크기를
  아무리 조정해도 `market.ticks`의 처리량은 단일 컨슈머 스레드에 묶인다. 다른 어떤 성능
  작업보다 이게 먼저다.
- **auto-create는 설정의 소유자를 지운다.** "누가 이 토픽을 이 파티션 수로 만들기로
  했는가"에 답할 수 없는 상태가 운영 환경에 있으면 안 된다. `NewTopic` 빈은 그 답을
  코드 리뷰 가능한 형태로 만든다.
- **환경별 파티션 수를 코드에 하드코딩하지 않는 이유**: 로컬 `docker compose up`이
  256개 파티션 디렉터리를 만들 이유가 없다. 같은 선언에서 값만 다르게 주입하면
  "로컬에서 되는데 운영에서 안 되는" 종류의 차이가 설정 파일 한 곳에만 남는다.
- **T2 목표값을 지금 표에 적는 이유**: 파티션은 줄일 수 없다. 나중에 결정하겠다고 미루면
  그때는 이미 잘못된 값으로 운영 중일 가능성이 높다. 값을 지금 정해두고 적용 시점만
  미룬다.
- **재시도/DLT 토픽을 함께 선언하는 이유**: 위 Context 3번. auto-create만 끄고 이걸
  놓치면 재시도 컨슈머가 존재하지 않는 토픽을 구독하며 조용히 실패한다 —
  ADR-006이 만든 안전장치가 통째로 무력화되는데 로그만 봐서는 알기 어렵다.

## Consequences

- **`KafkaAdmin`에 토픽 생성 권한이 필요하다.** 운영 환경에서 Kafka에 ACL을 걸면
  애플리케이션 계정에 `CREATE`/`DESCRIBE` 권한을 줘야 한다. 이게 부담스러운 조직이면
  토픽 생성을 IaC(Terraform Kafka provider 등)로 옮기고 `NewTopic` 빈은 검증 전용으로
  남기는 변형이 가능하다 — 그 경우 선언은 여전히 코드에 남는다.
- **`NewTopic`은 이미 존재하는 토픽의 파티션을 늘리지만 줄이거나 다른 설정을 바꾸지
  않는다.** retention 변경 등은 여전히 `kafka-configs --alter`가 필요하다.
- **재시도 토픽 선언이 어노테이션과 이중 관리된다.** `@RetryableTopic(attempts = "3")`을
  바꾸면 `KafkaTopicConfig`의 `retryFamily(..., attempts = 3)`도 바꿔야 한다.
  완화책으로 attempts 값을 공용 상수(`RetryPolicy.TICK_ATTEMPTS`)로 빼서 양쪽이 같은
  출처를 읽게 한다. 그래도 새 `@RetryableTopic`을 추가할 때 선언을 잊을 위험은 남으므로,
  **기동 시 "선언되지 않은 재시도 토픽" 검증 테스트**를 통합 테스트에 넣는다.
- **`trading.*`의 유실 방지는 아직 완결되지 않는다.** 복제 계수가 1인 동안
  `acks=all`은 실질적으로 `acks=1`이다. 브로커 노드가 죽으면 커밋된 주문 이벤트가
  유실될 수 있다. 이건 이 ADR이 해결하는 문제가 아니라 **드러내는** 문제다 —
  멀티 브로커 전환을 Phase 1에서 처리한다. 그때까지 Outbox
  ([ADR-008](008-outbox-pattern-spring-modulith.md))의 `event_publication` 테이블이
  최후의 방어선이다(미완료 이벤트 자동 재전송).
- 로컬 개발에서 토픽이 자동으로 생기지 않으므로, 새 토픽을 추가할 때 선언을 빠뜨리면
  **로컬에서도 즉시 실패한다.** 운영에서만 드러나는 것보다 낫다.

## Revisit When

- **스키마 레지스트리 도입 시** — 현재 `GeneratedTick`(Kotlin)/`Tick`(Go)/
  `MarketTickMessage`(Kotlin)는 필드명 관례로만 동기화된다
  (`MarketTickBroadcastConsumer`의 주석이 직접 인정하고 있다). 토픽 선언이 코드로 오면
  스키마도 같은 방식으로 관리하는 것이 자연스럽다. Phase 1의 별도 ADR로 다룬다.
- **멀티 브로커 전환 시** — 복제 계수 3, `min.insync.replicas=2`, rack awareness.
- **T2 파티션 목표에 도달할 때** — 표의 "T2 목표" 열을 적용하고, 증설 절차 §4를 따른다.
- 파티션당 처리량 가정(~2,000 msg/s)이 실측과 크게 다를 때 — 산정 근거가 틀렸다면
  목표값을 다시 계산한다. 이 가정은 `bench/`의 `tick-storm` 시나리오로 검증한다.
