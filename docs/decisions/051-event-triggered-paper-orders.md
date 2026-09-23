# ADR-051: 탐지된 이벤트로 모의투자 주문을 낸다 (watch rule)

## Status
Accepted

## Context

monticker는 "가격이 왜 움직였는가"를 이벤트로 정규화하는 데까지 왔다([ADR-003](003-stock-events-central.md)).
그런데 `stock_events`를 소비해 **행동으로 옮기는** 경로가 없다. 지금 이벤트를 읽는 곳은 전부 표시·검색이다:

| 소비자 | 하는 일 |
|---|---|
| `EventTimelineService` | 차트 타임라인에 표시 |
| `EventIndexer` / `EventSearchService` | ES 색인·검색([ADR-042](042-outbox-based-es-indexing.md)) |
| `StockEventWriter`의 Expo 푸시 | 관심종목 보유자에게 알림 |

사용자가 할 수 있는 "행동"은 알림을 받고 직접 주문하는 것뿐이다. `alert_rules`는 알림만 보내고,
Quant Lab 포워드 테스트는 신호를 기록만 한다([ADR-024](024-quant-lab-forward-test.md)).
`architecture.md`에 `PaperAutoTrader` 설계가 적혀 있었지만 코드는 없었다.

이벤트 → 주문을 이으면 "탐지는 잘 하는데 그 다음이 없다"는 간극이 메워진다. 동시에 **돈이 움직이는 경로**가
하나 더 생긴다 — 사용자가 화면 앞에 없을 때 서버가 스스로 주문을 낸다. 그래서 두 가지를 먼저 정해야 했다.

### 어디까지 자동화하나

[ADR-025](025-real-brokerage-order-safety-gate.md)는 실주문이 사전 리스크 게이트를 반드시 통과하게 했고,
[ADR-036](036-ai-order-proposal.md)은 자동화가 **제안까지만** 하고 제출은 사람이 하도록 선을 그었다.
watch rule은 그 선의 어느 쪽인가?

- 실브로커 계좌까지 자동 실행하면 ADR-036의 선을 넘는다. "승인 즉시 자동 체결로 새는 경로가 없다"는 성질이
  깨지고, 자본시장법 검토([legal-review-brief.md](../legal-review-brief.md)) 대상도 달라진다.
- 모의투자 계좌는 실제 자금이 아니다. 사용자가 **사전에 명시적으로** 조건·수량을 선언했다는 점에서
  LLM이 생성한 제안과도 성격이 다르다.

### 이벤트를 어떻게 전달하나

worker가 탐지하고 api가 주문을 낸다 — 프로세스가 다르다. 후보:

- **A) 기존 `market.events` 재사용** — `EventKafkaProducer`가 이미 발행한다.
- **B) 새 아웃박스 토픽** — [ADR-042](042-outbox-based-es-indexing.md)의 `search.index`와 같은 패턴.
- **C) api가 `stock_events`를 폴링** — 프로세스 간 결합 없음.

A는 세 가지가 걸린다. (1) `ingestion.source=kafka`일 때만 발행된다 — 기본 모드(internal)에서는 룰이 영영
발동하지 않는다. (2) 페이로드가 `DetectedEvent`라 `stock_events.id`가 없다 — 멱등 키로 쓸 값이 없다.
(3) 발행 큐가 차면 **가장 오래된 것부터 버린다**(베스트 에포트). 알림에는 맞는 선택이지만 주문에는 아니다.
게다가 이 토픽은 [ADR-033](033-remove-netty-broadcast-gateway.md)으로 Netty 게이트웨이가 사라진 뒤
컨슈머가 없다.

C는 폴링 간격만큼 지연되고, "어디까지 읽었나"를 따로 관리해야 한다 — 아웃박스가 이미 하는 일을 다시 만든다.

## Decision

**모의투자 계좌 전용**으로, **B(새 아웃박스 토픽)** 를 통해 이벤트를 전달한다.

```
worker: EventDetector → StockEventWriter
          │  (한 트랜잭션)
          ├── INSERT stock_events RETURNING id
          ├── publish SearchIndexEvent        (ADR-042, 색인)
          └── publish StockEventDetectedEvent (이 ADR)
                │  커밋 후 Modulith 외부화
                ▼
          Kafka market.event-detected  (key = stockId)
                │
api:      WatchRuleConsumer (groupId=monticker-watch-rule, @RetryableTopic 3회 → DLT)
                ▼
          WatchRuleExecutor
                ├── 활성 룰 조회 (stock_id, event_type)
                ├── 중요도 하한 / 쿨다운 → SKIPPED 기록
                └── matching::submit.submitMarket(idempotencyKey = "WR:{ruleId}:{eventId}")
                      → EXECUTED / REJECTED 기록
```

**실브로커 계좌 자동 실행은 범위 밖이다.** `watchrule` 모듈은 `brokerage`에 의존하지 않는다고
`package-info.java`에 선언했다 — 경계를 문서가 아니라 `ModulithStructureTest`가 강제한다.

주문은 `matching::submit`을 통하므로 사용자가 손으로 낸 주문과 **똑같이 리스크 게이트를 통과한다**.
우회 경로를 만들지 않는다([ADR-047](047-single-execution-path-for-paper-account.md)의 단일 체결 경로 유지).

### 정확히 한 번 체결

아웃박스는 at-least-once이고([ADR-008](008-outbox-pattern-spring-modulith.md)) 컨슈머 리밸런싱도 재소비를
만든다. 같은 (룰, 이벤트)가 여러 번 오는 것을 **정상으로 보고** 두 개의 DB 제약으로 수렴시킨다:

1. **주문 멱등 키** `orders.idempotency_key` 부분 유니크(V48). `WR:{ruleId}:{eventId}`로 두 번 제출하면
   두 번째는 새 주문을 만들지 않고 첫 체결을 그대로 돌려준다. **중복 체결을 막는 것은 이것뿐이다.**
2. **발동 기록 유니크** `(watch_rule_id, stock_event_id)`(V49). 기록이 두 줄 생기지 않는다.

애플리케이션의 사전 조회(`existsBy…`)는 빠른 경로일 뿐 방어선이 아니다 — 두 스레드가 동시에 통과할 수 있다.
`WatchRuleIdempotencyIntegrationTest`가 실제 Postgres에 10스레드를 동시에 쳐서 이를 검증한다.

기존 멱등성([ADR-007](007-idempotency-key-filter.md))은 `X-Idempotency-Key` **HTTP 필터**라 바깥에서 들어온
요청만 보호한다. 서버 내부 소비자는 그 필터를 타지 않으므로 필터 밖에 중복 경로가 생겼고, 그래서 키를
주문 테이블로 내렸다.

### 실패를 어떻게 남기나

| 상황 | 기록 | 이유 |
|---|---|---|
| 체결 | `EXECUTED` + orderId·체결가 | |
| 리스크 한도·잔고 부족·미체결 | `REJECTED` + 사유 | 사용자가 "왜 안 샀지"를 볼 수 있어야 한다 |
| 중요도 미달·쿨다운 | `SKIPPED` + 사유 | 위와 같음 |
| DB·네트워크 등 인프라 장애 | **기록하지 않음** | 기록하면 멱등 키가 잡혀 재시도가 영영 막히고, 사용자에게는 "거부됨"으로 보인다 |

재시도 3회 후에도 실패하면 DLT로 보내고 **자동 재처리하지 않는다**. 돈이 움직이는 경로에서
"한참 뒤에 자동으로 체결됨"은 사용자가 예상할 수 없는 동작이다 —
원장 불일치와 같은 원칙이다([ledger-mismatch.md](../runbooks/ledger-mismatch.md)): 사람이 보고 결정한다.

## Reasons

- **탐지 로직을 건드리지 않는다.** `EventDetector`·`stock_events`는 그대로이고 소비자만 하나 늘었다.
  같은 트랜잭션에 발행 한 줄을 더하는 것이 변경의 전부다.
- **아웃박스가 이미 검증된 경로다.** ADR-042가 같은 메커니즘으로 색인을 옮기고 있고, 브로커 장애 하에서
  재전송이 동작하는 것을 CH-05에서 확인했다([resilience-plan §6.3](../resilience-plan.md)).
- **멱등성을 DB에 맡겼다.** 애플리케이션 레벨 "조회 후 판단"은 동시 소비에서 새어나간다. 제약은 안 샌다.
- **모의투자 한정이 기존 결정과 충돌하지 않는다.** ADR-036의 "자동화는 제안까지"는 실계좌에 관한 선이고,
  모의 계좌에서 사용자가 사전 선언한 규칙은 그 선 안쪽이다. 의존성으로 경계를 고정해 나중에 무심코
  실계좌로 확장되는 것을 막았다.

## Consequences

- **토픽이 하나 늘었다.** `market.event-detected`는 `market.events`와 이름이 비슷해 혼동될 수 있다.
  후자는 컨슈머가 없는 채로 남아 있다 — 정리는 별도 작업이다.
- **worker와 api가 이벤트 DTO를 공유하지 않고 복제한다.** `StockEventDetectedEvent`가 양쪽에 있고
  JSON 형태가 같아야 한다. ADR-042의 `SearchIndexEvent`와 같은 방식이고 같은 위험(한쪽만 고치면 깨짐)이 있다.
  필드 추가는 기본값과 함께, 삭제는 두 배포에 걸쳐 한다.
- **룰이 많아지면 이벤트당 주문 시도가 늘어난다.** 지금은 이벤트마다 `(stock_id, event_type)` 인덱스로
  룰을 찾아 순차 처리한다. 한 종목에 수천 개의 룰이 걸리면 컨슈머 지연이 커진다 — 파티션 키가 stockId라
  같은 종목의 룰이 한 컨슈머에 몰리는 구조이기도 하다.
- **쿨다운은 EXECUTED만 센다.** 거부가 반복되면 이벤트마다 주문을 다시 시도한다. 리스크 게이트가
  매번 막아주지만 시도 자체는 계속된다.
- **DLT에 쌓인 이벤트는 사람이 처리해야 한다.** 자동 재처리를 하지 않기로 한 대가다.

## Revisit When

- 실계좌 자동 실행 요구가 생겼을 때 — 이 ADR을 번복하는 새 ADR과 법무 검토가 먼저다.
- 한 종목에 걸린 룰이 수백 개를 넘어 컨슈머 지연이 보일 때 — 룰 평가를 배치로 묶거나 파티션 키를 바꾼다.
- `market.events`를 정리할 때 — 이 토픽과 합칠지 결정한다.
- 조건이 "이벤트 발생"보다 복잡해질 때(지표 조합 등) — Quant Lab 룰 엔진과 통합할지 검토한다.
