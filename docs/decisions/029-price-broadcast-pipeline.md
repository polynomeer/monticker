# ADR-029: 실시간 시세 푸시 파이프라인 복구

## Status
Accepted

## Context

실시간 시세 파이프라인을 Mock에서 실데이터(KIS/Toss)로 전환하는 작업에 착수하기 전, 현재 파이프라인을 끝까지 추적해보니 **Mock이든 실데이터든 지금은 프론트엔드에 실시간 푸시가 전혀 가지 않는다**는 훨씬 근본적인 문제를 발견했다.

**확인된 실제 경로**:
```
Kafka[market.ticks] → TickKafkaConsumer(backend/worker) → RedisTickWriter → Redis stock:price:{market}:{symbol}
```
여기서 끝난다. `PriceBroadcaster`(`backend/api/.../marketdata/infrastructure/PriceBroadcaster.kt`)가 STOMP `/topic/stocks/{id}`·`/topic/market`으로 발행하는 유일한 컴포넌트인데, **저장소 전체를 grep해도 이걸 호출하는 곳이 없다.** [ADR-005](005-kafka-go-gateway-netty-broadcast.md) 본문이 이미 이 사실을 명시했다("`PriceBroadcaster`... 어떤 스케줄러도 실제로 호출한 적이 없다") — Netty `broadcast-gateway`로 대체하겠다고 했지만 실제로 프론트엔드가 그 프로토콜을 쓰도록 바꾸지 않았고, `PriceBroadcaster` 자체도 고치지 않은 채 남겨뒀다.

반면 `apps/web`의 `useStockPrice.ts`/`useMarketPricesWs.ts`는 STOMP(`@stomp/stompjs`+`sockjs-client`)로 `/topic/stocks/{id}`·`/topic/market`을 구독한다 — 정확히 `PriceBroadcaster`가 발행해야 할 그 목적지다. 지금은 마운트 시 REST 1회 조회(`GET /api/stocks/{id}/price`, `RedisPriceCache` 경유)로 초기값만 받고, 그 이후로는 값이 고정된다.

**`backend/api`와 `backend/worker`는 별도 배포 단위(별도 JVM)다** — `TickKafkaConsumer`가 있는 worker에서 `PriceBroadcaster`(api)를 직접 메서드 호출로 연결할 수 없다. 두 서비스를 잇는 방법이 필요하다.

**추가로 라이브 테스트 중 발견한, 이 작업과 직접 얽힌 별도 버그**: `backend/worker`의 `application.yml`에 `spring.kafka.bootstrap-servers`가 전혀 설정돼 있지 않았다. `TickKafkaConsumer`(`@KafkaListener`)는 `ingestion.source=kafka`가 아닌 한(기본값 `internal`) Spring Boot 자동구성 `ConsumerFactory`를 쓰는데, 이건 Spring Boot 자체 기본값(`localhost:9092`)에 접속한다 — `KAFKA_BROKERS` 환경변수를 설정해도 무시된다. 반면 수동 생성되는 `TickKafkaProducer`와 조건부(`ingestion.source=kafka`)로만 활성화되는 `KafkaConfig.kt`는 별도의 비-spring 네임스페이스 커스텀 프로퍼티(`kafka.brokers`)를 읽어 `KAFKA_BROKERS`를 제대로 반영한다. 즉 프로듀서와 컨슈머가 서로 다른 프로퍼티 키를 봐서, 기본 모드(`ingestion.source=internal`)에서는 컨슈머만 `KAFKA_BROKERS`를 무시하고 있었다 — 이 세션에서 로컬 검증용으로 9092 포트를 다른 프로세스(같은 머신의 무관한 프로젝트 Kafka 호환 브로커)가 점유하고 있던 상황에서 이 버그가 실제로 발현되어, 워커가 의도치 않게 그 무관한 브로커에 토픽을 만들고 데이터를 발행하는 사고로 이어졌다(정리 완료).

## Decision

1. **`backend/api`에 `market.ticks` 전용 Kafka 컨슈머를 신설**(`MarketTickBroadcastConsumer`, `groupId=monticker-api-broadcast`, worker의 `monticker-worker` 그룹과 완전히 독립) — 틱을 파싱해 `PriceBroadcaster.broadcast(...)`를 호출한다. Kafka는 이미 두 서비스가 공유하는 유일한 실시간 버스이자, ADR-005가 스스로 "여러 미래 컨슈머를 위한 durable/replayable 디커플링"이라고 명시한 목적 그대로다 — 새 메커니즘(Redis Pub/Sub 등)을 도입하지 않고 이미 있는 것을 그대로 확장한다.

2. **`auto-offset-reset=latest`** — 새 컨슈머 그룹은 항상 최신 틱부터 받는다. 브로드캐스트는 "지금 가격"이 의미 있지, 재시작 시점에 밀려있던 과거 틱을 전부 다시 쏟아낼 이유가 없다(과거 틱 재생은 `monticker-worker` 그룹의 몫이며 이미 그렇게 동작한다).

3. **`backend/worker`의 `spring.kafka.bootstrap-servers` 설정 추가** — `KAFKA_BROKERS` 환경변수가 프로듀서/컨슈머 양쪽에 실제로 반영되도록 고친다. 이번 작업과 직접 얽혀 라이브 테스트 중 발견했고, 고치지 않으면 새 브로드캐스트 컨슈머를 검증하는 것 자체가 불가능했다.

4. **`PriceBroadcaster` 자체는 수정하지 않는다** — 이미 올바르게 구현돼 있었고, 그저 아무도 호출하지 않았을 뿐이다.

## Reasons

- **Kafka 직접 구독 vs Redis Pub/Sub 신설**: `backend/api`는 이미 `spring-kafka` 의존성과 `spring.kafka.bootstrap-servers` 설정을 갖고 있다(다른 목적으로 추가돼 있었으나 실제 소비자는 없었다) — 새 인프라를 추가하지 않고 기존 의존성을 실제로 쓰는 것이 더 단순하다. Redis Pub/Sub은 최소 하나의 서비스(worker)에 발행 코드를 새로 추가해야 하는 건 똑같고, Kafka의 재시도/DLT/파티션 순서 보장이 없다.
- **`monticker-api-broadcast`를 별도 컨슈머 그룹으로**: 같은 토픽을 여러 독립 컨슈머 그룹이 읽는 건 Kafka의 표준 패턴이다 — worker의 `monticker-worker` 그룹과 오프셋을 공유하지 않아야 서로의 리밸런스나 장애가 상대에게 전파되지 않는다.
- **`latest`만 쓰는 이유**: 이 세션 로컬 검증 중 실제로 confirmedKafka 파티션에 이전 실행에서 남은 백로그가 수천 건 쌓여 있었다(컨테이너 재시작에도 데이터가 보존되는 볼륨 때문) — `earliest`였다면 새 브로드캐스트 컨슈머가 뜨는 순간 오래된 가격을 한꺼번에 프론트에 쏟아보낼 뻔했다.

## Consequences

- `backend/api`가 이제 Kafka 브로커에 실제로 의존한다(컨슈머 시작) — 지금까지는 의존성만 있고 실제 연결은 없었다. 로컬 개발 시 Kafka가 안 떠 있으면 이 컨슈머는 연결 재시도를 반복하며 로그에 경고를 남기지만(Spring Kafka 기본 동작), api 앱 자체의 부팅을 막지는 않는다 — 확인됨.
- `market.ticks` 토픽에 이제 두 개의 독립 컨슈머 그룹(worker 처리용, api 브로드캐스트용)이 붙는다 — 토픽 파티션 수가 1인 현재 설정에서는 컨슈머가 늘어도 처리량 문제가 되지 않는다(각 그룹이 독립적으로 전체 파티션을 읽는다).
- 여전히 Mock 데이터 그대로다 — 이 ADR은 "실시간으로 뜨는가"만 고치고, "실제 가격인가"는 다음 라운드(KIS/Toss 실데이터 연동)의 몫이다.
- Netty `broadcast-gateway`(ADR-005)는 여전히 아무도 안 쓰는 상태로 남는다 — 이번 ADR은 그걸 없애거나 프론트를 거기로 옮기지 않았다. 두 개의 실시간 전송 경로(STOMP, 이번에 살림 / 커스텀 WS, 여전히 죽음)가 공존하는 상태다.

## Revisit When

- KIS/Toss 실데이터 연동 시 — `market.ticks`에 실제 시세가 흐르기 시작하면 이 브로드캐스트 경로가 그대로 실제 가격을 실어 나른다(추가 변경 불필요, 이게 이 설계의 핵심 이점이다).
- Netty `broadcast-gateway`를 실제로 채택하거나 완전히 제거하기로 할 때 — 지금처럼 죽은 코드로 남겨두는 대신 결정을 내려야 한다.
- 토픽 파티션을 늘려 병렬 처리가 필요해질 때 — api 브로드캐스트 컨슈머도 파티션 수만큼 스케일해야 한다.
