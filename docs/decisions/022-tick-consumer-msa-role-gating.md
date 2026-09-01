# ADR-022: msa 프로필 3중 market.ticks 중복 소비 제거

## Status
Accepted

## Context

[ADR-021](021-candles-1d-realtime-upsert.md)의 "후속 수정 (2026-09-01)"에서 스코프 밖으로
분리했던 문제를 조사했다: `docker-compose.yml`의 `--profile msa`는 `worker-market`/
`worker-event`/`worker-alert` 3개 컨테이너를 각각 `WORKER_ROLE=market|event|alert`로 띄운다.

`TickKafkaConsumer`(`market.ticks` 토픽, `groupId=monticker-worker`)의 활성화 조건은:

```kotlin
@ConditionalOnExpression(
    "'\${worker.role:all}' == 'event' || '\${tick.consumer:legacy}' == 'legacy'"
)
```

`tick.consumer`는 `docker-compose.yml`의 세 worker-* 서비스 어디에서도 환경변수로
오버라이드되지 않아 항상 기본값 `legacy`다. 즉 두 번째 항 `tick.consumer == 'legacy'`가
role에 상관없이 항상 참이 되어, 의도한 `role=event`뿐 아니라 `role=market`/`role=alert`
에서도 이 조건이 참이 된다. 결과적으로 msa 프로필의 세 프로세스가 전부 같은 컨슈머
그룹으로 `market.ticks`를 동시 소비하며 `CandleAggregator.onTick()`(분봉/일봉 인메모리
집계), `RedisTickWriter.write()`, `EventDetector.detect()`를 중복 실행하고 있었다.

프로세스 내부 스레드 레이스는 없다(각 프로세스가 이 토픽에 대해 단일 스레드 컨슈머).
그러나 세 프로세스가 같은 컨슈머 그룹에 속해 있으므로 롤링 재시작 등으로 파티션
리밸런스가 발생하면, `CandleAggregator`가 `ConsumerRebalanceListener` 없이 인메모리
`state`(진행 중인 분봉)를 들고 있다가 파티션을 뺏기는 프로세스가 있을 수 있다 —
그 프로세스가 담당하던 분봉은 다음 분 경계까지 flush되지 않은 상태였다면 그대로
유실된다(`candles_1m`/`candles_1d` 어디에도 기록되지 않음).

각 role이 실제로 무엇을 필요로 하는지 다른 role-게이팅 빈들을 대조해 확인했다:

- **`worker-market`**: `MarketTickScheduler`(`role.matches('market|all')`)가
  `MockPriceGenerator` 틱을 `TickKafkaProducer`(`role.matches('market|all')`)로
  `market.ticks`에 발행하는 역할만 한다. 틱을 스스로 소비할 이유가 없다.
- **`worker-event`**: `TickProcessedKafkaProducer`(`role == 'event'`)가 존재 —
  `TickKafkaConsumer.onTick()`이 이벤트 탐지까지 마친 뒤 `market.tick-processed`
  토픽으로 발행하는 producer다. role=event는 설계상 원래부터 `market.ticks`를
  소비하는 유일한 역할이다.
- **`worker-alert`**: `AlertKafkaConsumer`(`role == 'alert'`)가 **별도 컨슈머 그룹**
  (`groupId=monticker-alert-worker`)으로 `market.tick-processed`(`market.ticks`가
  아니다)를 소비해 `AlertEvaluator.processAlert()`를 호출한다. VOLUME_SURGE 룰은
  `candles_1d`를 직접 SQL로 읽으므로 `CandleAggregator`의 인메모리 상태도 필요 없다.
  즉 alert 역할은 `market.ticks`도, `CandleAggregator.onTick()`도 애초에 필요하지 않다.

`TickKafkaConsumer` 클래스 상단 주석("role=all: tick.consumer=legacy일 때 활성화 / role=event:
항상 활성화")도 원래 role=market·alert는 대상이 아니었음을 보여준다 — `@ConditionalOnExpression`
불리언 조건이 그 의도를 정확히 반영하지 못했을 뿐이다.

## Decision

후보 A) 활성화 조건을 role=event·role=all(legacy)로만 좁혀 중복 소비를 원천 제거.
후보 B) 다중 프로세스 소비를 유지하되 `ConsumerRebalanceListener`로 리밸런스 시
`CandleAggregator.flushAll()`을 호출해 유실을 막는다.

**A안을 선택**했다. 위 조사에서 확인했듯 `worker-market`/`worker-alert`는
`market.ticks`를 소비할 필요가 원래 없다 — B안은 애초에 존재해서는 안 되는 중복
소비를 전제로 그 위에 리밸런스 안전장치를 얹는 것이라 불필요한 복잡성이다.

`TickKafkaConsumer`의 조건을 다음과 같이 수정했다(`TickKafkaConsumer.kt`):

```kotlin
@ConditionalOnExpression(
    "'\${worker.role:all}' == 'event' || " +
    "('\${worker.role:all}' == 'all' && '\${tick.consumer:legacy}' == 'legacy'")
)
```

`tick.consumer == 'legacy'` 단독 조건을 `worker.role == 'all'`과 AND로 묶어, role=market/
alert에서는 `tick.consumer` 값과 무관하게 항상 비활성화되도록 했다.

## Reasons

- 세 role의 실제 책임(발행 전용 / 소비+집계+탐지 / 별도 토픽 소비)을 대조한 결과
  `market.ticks`를 동시에 여러 프로세스가 소비해야 할 시나리오가 없다 — 중복 소비는
  버그이지 의도된 아키텍처가 아니다.
- 근본 원인(조건식이 설계 의도를 잘못 인코딩)을 고치는 편이, 잘못된 전제 위에
  리밸런스 리스너·체크포인팅을 새로 설계하는 것보다 훨씬 적은 코드 변경으로 문제를
  완전히 없앤다.
- `CandleAggregator`/`RedisTickWriter`/`EventDetector`가 정확히 한 프로세스
  (`role=event`, 또는 단일 프로세스 배포의 `role=all`)에서만 실행되므로, 파티션
  리밸런스가 일어나도 애초에 그 토픽을 소비하는 프로세스가 하나뿐이라 리밸런스로
  인한 소유권 이전 자체가 발생하지 않는다(파티션이 옮겨갈 다른 컨슈머가 없다 —
  `role=event` 프로세스가 여러 개로 스케일아웃되지 않는 한).

## Consequences

- msa 프로필에서 `market.ticks` 컨슈머 그룹(`monticker-worker`)의 멤버는 이제
  `worker-event` 프로세스 하나뿐이다. `worker-event`가 다운되면 캔들 집계/이벤트
  탐지/알림 전파가 전부 멈춘다 — 이전에도 사실상 그랬다(다른 두 프로세스가 중복
  소비하고 있었을 뿐, 셋 다 같은 컨슈머 그룹이라 파티션이 나뉘어 있어 한 프로세스가
  죽어도 다른 프로세스가 그 파티션을 이어받는 것 자체는 원래도 가능했다). `role=event`를
  단일 장애점으로 만들고 싶지 않다면 `worker-event`를 여러 인스턴스로 스케일아웃하는
  것을 향후 고려할 수 있다(그 경우엔 진짜로 `ConsumerRebalanceListener`가 필요해진다 —
  아래 Revisit When 참고).
- `worker-market`/`worker-alert` 프로세스는 이제 `CandleAggregator`/`RedisTickWriter`/
  `EventDetector` 빈은 여전히 생성되지만(role 조건 없는 무조건 `@Component`) 실제로
  틱을 받아 호출되는 경로가 없다 — 죽은 코드 경로는 아니고 단지 미사용 상태다. 정리는
  이번 스코프 밖으로 남긴다(각 컴포넌트를 role 조건부로 만들면 단일 프로세스(`role=all`)
  배포와 msa 배포의 빈 그래프가 더 갈라져 유지보수 부담이 커진다).
- 단일 프로세스 배포(`role=all`, 기본값)는 이번 변경으로 동작이 바뀌지 않는다 —
  `role=='all' && tick.consumer=='legacy'`는 기존 `tick.consumer=='legacy'` 단독
  조건과 동일하게 참이다.

## Revisit When

- `worker-event`를 다중 인스턴스로 스케일아웃하게 되면(트래픽 증가 대응 등), 그때는
  진짜로 같은 컨슈머 그룹 내 여러 프로세스가 `market.ticks`를 나눠 소비하게 되므로
  `CandleAggregator`에 `ConsumerRebalanceListener`(`onPartitionsRevoked` 시
  `flushAll()`)를 추가해야 한다 — `@PreDestroy fun onShutdown() = flushAll()`과
  동일한 패턴을 리밸런스 이벤트에도 적용.
