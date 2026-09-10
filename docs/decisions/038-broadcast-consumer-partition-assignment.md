# ADR-038: 시세 브로드캐스트 컨슈머 — 컨슈머 그룹 분할 대신 전 파티션 수동 할당

## Status
Accepted

## Context

[scale-out-plan.md](../scale-out-plan.md) §3.1에서 발견한, **현재 prod 설정에서 이미 발현
중인 버그**다. 용량 문제가 아니라 정확성 문제라 Phase 0 최우선으로 올렸다.

실시간 시세 푸시 경로는 [ADR-029](029-price-broadcast-pipeline.md)가 만들었다:

```
Kafka market.ticks
  → MarketTickBroadcastConsumer (@KafkaListener, groupId="monticker-api-broadcast")
    → PriceBroadcaster → SimpMessagingTemplate → /topic/stocks/{id}
```

두 컴포넌트의 조합에 문제가 있다:

1. **STOMP 브로커가 pod 로컬이다.** `WebSocketConfig`는
   `registry.enableSimpleBroker("/topic")` — Spring의 인메모리 SimpleBroker다.
   pod A가 발행한 메시지는 pod A에 붙은 세션에만 간다.
2. **모든 pod가 같은 컨슈머 그룹으로 구독한다.** `groupId = "monticker-api-broadcast"`가
   하드코딩돼 있으므로, api 인스턴스가 여러 개면 Kafka가 **파티션을 인스턴스끼리 나눠준다.**

즉 pod B가 소비하지 않은 파티션의 틱은 **pod B에 붙은 클라이언트에게 영원히 도달하지
않는다.** 그리고 `infra/k8s/base/api.yaml`은 `replicas: 2`, prod overlay는
`minReplicas: 3`이다 — 조건이 이미 충족돼 있다.

지금 `market.ticks`의 파티션이 1개([ADR-040](040-kafka-topic-declaration.md)에서 다룬다)라
증상은 "한 pod가 전부 받고 나머지 pod의 클라이언트는 아무 틱도 못 받는" 형태로 나타난다.
같은 종목을 보는 두 사용자가 어느 pod에 붙었느냐에 따라 실시간성이 갈린다.

ADR-029의 Consequences는 이렇게 적었다:

> `market.ticks` 토픽에 이제 두 개의 독립 컨슈머 그룹이 붙는다 — 토픽 파티션 수가 1인
> 현재 설정에서는 컨슈머가 늘어도 처리량 문제가 되지 않는다(각 그룹이 독립적으로 전체
> 파티션을 읽는다).

**"각 그룹이 독립적으로 전체 파티션을 읽는다"는 맞지만, "그룹 안의 각 인스턴스가 전체
파티션을 읽는다"는 틀리다.** 당시 api 인스턴스가 1개라는 암묵적 전제 위에서만 참이었고,
그 전제는 K8s 매니페스트에서 이미 깨져 있었다. ADR-029의 Revisit When 마지막 항목
("토픽 파티션을 늘려 병렬 처리가 필요해질 때 — api 브로드캐스트 컨슈머도 파티션 수만큼
스케일해야 한다")이 같은 문제를 절반쯤 예고하고 있었다.

**후보**:

- **A) 인스턴스마다 고유 `group.id`** (`monticker-api-broadcast-${HOSTNAME}`) — 각 인스턴스가
  전 파티션을 받게 된다. 한 줄 변경.
- **B) 전 파티션 수동 할당(`assign`)** — 컨슈머 그룹을 아예 쓰지 않는다.
- **C) 외부 STOMP 브로커 릴레이** (`enableStompBrokerRelay` → RabbitMQ) — 브로커가 pod 간
  메시지를 전파한다.
- **D) fan-out 전용 서비스 신설** — WebSocket 책임을 api에서 분리.

## Decision

**B안**을 채택한다. 추가로 conflation을 함께 도입한다.

### 1. 브로드캐스트 컨슈머는 컨슈머 그룹 분할에 의존하지 않는다

`@KafkaListener(groupId=...)`를 버리고, 기동 시 파티션 메타데이터를 조회해 **전 파티션을
수동 할당**하고 각 파티션의 끝으로 seek한다. 오프셋을 커밋하지 않는다.

```kotlin
// MarketTickBroadcastConsumer — @KafkaListener 제거, 컨테이너 직접 구성
@Bean
fun tickBroadcastContainer(
    consumerFactory: ConsumerFactory<String, String>,
    admin: KafkaAdmin,
    handler: MarketTickBroadcastHandler,
): ConcurrentMessageListenerContainer<String, String> {
    // 파티션 수는 기동 시점에 브로커에서 읽는다 — ADR-040으로 파티션을 늘려도 코드 변경 없음
    val partitionCount = admin.describeTopics("market.ticks")["market.ticks"]!!.partitions().size
    val assignments = (0 until partitionCount).map {
        TopicPartitionOffset("market.ticks", it, TopicPartitionOffset.SeekPosition.END)
    }.toTypedArray()

    val props = ContainerProperties(*assignments).apply {
        messageListener = handler
        ackMode = ContainerProperties.AckMode.MANUAL   // 커밋하지 않는다
    }
    return ConcurrentMessageListenerContainer(consumerFactory, props)
}
```

### 2. `PriceBroadcaster`에 conflation을 넣는다

즉시 발행을 버퍼 + 주기 flush로 바꾼다. 같은 종목의 갱신은 **큐에 쌓지 않고 덮어쓴다.**

```kotlin
private val buffer = ConcurrentHashMap<Long, PriceTick>()   // stockId → 최신 틱

fun broadcast(tick: PriceTick) { buffer[tick.stockId] = tick }   // 즉시 발행하지 않는다

@Scheduled(fixedRate = 100)   // 10Hz
fun flush() {
    val snapshot = buffer.keys.toList()
    for (stockId in snapshot) {
        val tick = buffer.remove(stockId) ?: continue
        messagingTemplate.convertAndSend("/topic/stocks/$stockId", payload(tick))
    }
}
```

종목당 최대 10 msg/s로 상한이 걸린다. 시세는 **최신값만 의미가 있으므로** 중간 틱을
버려도 사용자에게 보이는 값은 동일하다.

### 3. fan-out 전용 티어는 지금 만들지 않는다

D안(그리고 [ADR-033](033-remove-netty-broadcast-gateway.md)이 지운 broadcast-gateway의
재도입)은 **채택하지 않는다.** ADR-033은 그대로 유효하며 Superseded로 바꾸지 않는다.
티어 분리 트리거는 Revisit When에 명시한다.

## Reasons

- **정확성 결함을 가장 작은 변경으로 고친다.** B안은 파일 1개, 신규 인프라 0개다.
  이 버그는 사용자에게 "시세가 안 움직인다"로 보이므로 큰 설계 작업 뒤에 숨겨둘 수 없다.
- **A안보다 B안이 나은 이유**: 고유 `group.id`는 pod가 재시작할 때마다 새 컨슈머 그룹을
  만든다. 그룹 메타데이터가 브로커에 계속 쌓이고(`offsets.retention.minutes`에 의존),
  `kafka-consumer-groups --list`가 쓰레기로 가득 찬다. 수동 할당은 그룹 자체를 만들지
  않으므로 이 문제가 없고, 리밸런스도 없다(브로드캐스트에는 리밸런스가 필요 없다 —
  모든 인스턴스가 모든 것을 원한다).
- **C안(외부 브로커 릴레이)을 지금 도입하지 않는 이유**: RabbitMQ/ActiveMQ를 운영 인프라에
  추가하는 비용이 크고, 브로커가 새로운 단일 병목이 된다(동접 20만 × 구독 20 = 400만 구독
  관계를 한 브로커가 관리하게 된다). 무엇보다 conflation을 브로커 레벨에서 할 수 없다 —
  릴레이는 메시지를 성실하게 전부 전달하는 게 일이다.
- **D안(전용 티어)을 지금 만들지 않는 이유**: ADR-033이 정확히 이 교훈을 남겼다 —
  "프론트엔드 클라이언트가 한 번도 존재한 적 없는" 게이트웨이를 만들어놓고 방치했다.
  같은 실수를 반복하지 않으려면 티어 분리는 **실측된 병목**이 있을 때, 프론트엔드 전환을
  같은 작업 단위에서 끝낼 수 있을 때만 해야 한다. 지금 필요한 건 정확성이지 처리량이 아니다.
- **conflation을 지금 함께 넣는 이유**: [ADR-040](040-kafka-topic-declaration.md)으로
  파티션을 늘리면 인스턴스당 소비량이 지금보다 늘어난다. 발행량 상한이 없으면 파티션
  증설이 곧바로 STOMP 발행 폭증으로 이어진다. 두 변경은 함께 가야 한다.

## Consequences

- **인스턴스마다 전체 틱 스트림을 소비한다.** api pod가 N개면 브로커 아웃바운드가 N배다.
  T2 추정(30,000 msg/s × ~200B × 40 pod ≈ 240 MB/s)에서는 감당 가능하지만, 이게 문제가
  되는 시점이 티어 분리 트리거 중 하나다.
- **`@RetryableTopic`/DLT를 이 컨슈머에 쓸 수 없다.** 재시도 토픽 메커니즘은 컨슈머 그룹을
  전제한다. 브로드캐스트는 유실 허용 경로이므로 문제되지 않는다 — 현재도
  `runCatching { }.onFailure { log.warn }`으로 실패를 삼키고 있고, 그게 이 경로에서는
  올바른 동작이다(다음 틱이 곧 온다). 단, **실패율을 메트릭으로 노출**해야 조용한 고장을
  감지할 수 있다.
- **오프셋을 커밋하지 않으므로 재시작 시 항상 최신부터 받는다.** ADR-029가
  `auto-offset-reset=latest`로 의도했던 동작이 더 명시적으로 보장된다.
- **파티션을 늘려도 코드 변경이 없다.** 기동 시 브로커에서 파티션 수를 읽으므로
  ADR-040의 파티션 증설이 이 컨슈머를 깨뜨리지 않는다. 단 **파티션 증설 후에는 재기동이
  필요하다**(할당은 기동 시 1회) — 배포 순서상 주의.
- **conflation으로 클라이언트가 받는 메시지 수가 줄어든다.** 초당 20틱이 들어오는 종목도
  10 msg/s로 상한이 걸린다. 차트의 틱 단위 정밀도가 필요한 화면이 생기면 별도 경로가
  필요하다(현재 그런 화면은 없다 — `useStockPrice`는 마지막 가격만 표시한다).
- **`PriceBroadcaster`가 상태를 갖게 된다.** 버퍼는 pod 로컬이고 유실 허용이므로
  수평 확장에 영향이 없다. 종료 시 flush는 하지 않는다 — 시세는 재연결 후 최신값을
  받으면 그만이다.

## Revisit When

- **WS 동접이 pod당 5,000을 넘거나**, api pod CPU의 30% 이상이 fan-out(직렬화·발행)에
  쓰이는 것이 실측될 때 → fan-out 전용 티어 분리(scale-out-plan §6.1.1 B안).
  그때는 **기존 STOMP 프로토콜과 토픽 이름을 그대로 유지**해 프론트엔드 변경 없이
  ingress 라우팅만 바꾸는 형태로 설계한다. ADR-033의 교훈이 여기에 적용된다.
- 브로커 아웃바운드(인스턴스 수 × 전체 틱 스트림)가 비용/대역폭 문제가 될 때 →
  게이트웨이를 종목 샤드로 나누고 클라이언트를 구독 종목 해시로 라우팅한다(셀 아키텍처).
- conflation 주기 100ms가 사용자 체감에 부족하다는 피드백이 실제로 나올 때 —
  주기를 줄이기 전에 "사람이 초당 몇 번 갱신되는 숫자를 읽을 수 있는가"를 먼저 확인한다.
