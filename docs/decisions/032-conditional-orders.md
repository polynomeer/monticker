# ADR-032: 조건부 주문(스탑로스/익절/OCO)

## Status
Accepted

## Context

`BrokerageOrderRequest`/`BrokerageClient`는 MARKET/LIMIT 즉시 제출만 지원한다 — 가격 조건이 충족될 때까지 기다렸다가 발동하는 주문(스탑로스, 익절, 특정가 돌파 매수 등)은 전혀 없다. `docs/product.md`/[ADR-023](023-commercialization-pivot.md)이 이미 이걸 로드맵 항목으로 명시했었다("`Order` 도메인은 현재 MARKET/LIMIT만 지원 — 기존 OMS를 대체하지 않고 그 위에 얹는 감시 컴포넌트로 설계").

**Toss는 네이티브 조건주문 엔드포인트(`/api/v1/conditional-orders`, OCO/OTO)가 스펙상 존재하지만, [ADR-026](026-toss-brokerage-integration.md)이 명시적으로 범위 밖으로 미뤄뒀고 이번에도 실제로 검증하지 않았다** — 이게 서버측에 주문을 보유해뒀다가 스스로 발동시키는 구조인지, 아니면 다른 형태인지 확인된 바 없다. KIS는 이런 엔드포인트 자체가 없다(`KisBrokerageClient.kt`에 조건주문 관련 코드/TODO 전무).

**브로커 네이티브 조건주문을 쓰면 [ADR-025](025-real-brokerage-order-safety-gate.md)의 사전 리스크 게이트를 우회하게 된다는 게 이번 결정의 핵심이다.** 리스크 게이트(`RiskCheckerService`)는 `BrokerageService.submitOrder()` 안에서만 호출된다 — 브로커가 며칠 뒤 스스로 조건주문을 발동시키면 그 시점의 실제 체결은 이 게이트를 절대 거치지 않는다. 이 세션 전체가 실거래 안전장치(ADR-025, ADR-027의 재인증 처리, ADR-028의 실제 취소 전달)에 공을 들여온 만큼, 신뢰가 검증되지 않은 브로커측 자동 발동 경로에 그 안전장치를 우회당하는 걸 받아들일 이유가 없다.

**마침 이번에 조건부 주문을 실시간으로 감시할 인프라가 이미 갖춰져 있다** — [ADR-029](029-price-broadcast-pipeline.md)가 `backend/api`에 `MarketTickBroadcastConsumer`(자체 Kafka 컨슈머, `market.ticks` 직접 구독)를 만들어뒀다. `BrokerageService`도 같은 프로세스(`backend/api`)에 있으므로, worker↔api IPC 브리지 없이 같은 JVM 안에서 "가격 감시 → 조건 충족 → 기존 submitOrder() 호출"을 그대로 연결할 수 있다.

**사용자 결정** (AskUserQuestion):
1. 브로커 네이티브 조건주문을 전혀 쓰지 않고 monticker 자체 감시 엔진으로 통일한다.
2. 1라운드 범위: 단일 트리거(STOP_LOSS/TAKE_PROFIT/PRICE_ABOVE/PRICE_BELOW) + OCO 페어링. OTO(주문 체결이 트리거)는 완전히 다른 메커니즘(가격이 아니라 체결 이벤트)이 필요해 별도 라운드로 미룬다.

## Decision

1. **새 테이블 `conditional_orders`** — `brokerage_orders`와 별개다. `brokerage_orders`는 이미 브로커에 제출된 주문만을 위한 하드 CHECK 제약(`order_type IN ('MARKET','LIMIT')`, `status IN ('SUBMITTED','FILLED',...)`)이 있어, "아직 제출 안 된 대기 중 조건"을 억지로 끼워 넣기보다 별도 도메인으로 둔다. 발동되면 기존 `BrokerageService.submitOrder()`를 그대로 호출해 `brokerage_orders`에 진짜 주문 행이 생기고, `conditional_orders.executed_order_id`가 그 행을 가리킨다.

2. **트리거 타입 4종을 비교 방향 2가지로 매핑**: `STOP_LOSS`/`PRICE_BELOW`는 "현재가 ≤ trigger_price", `TAKE_PROFIT`/`PRICE_ABOVE`는 "현재가 ≥ trigger_price". 타입을 4개로 나눈 건 UX·주문 내역 가독성을 위해서고("스탑로스 발동" vs "가격 이하 조건 발동"), 평가 로직은 비교 방향 하나만 본다.

3. **이벤트 브리지**: `MarketTickBroadcastConsumer.onTick()`이 `PriceBroadcaster.broadcast()` 호출 직후 `ApplicationEventPublisher`로 `MarketTickReceivedEvent`를 발행한다. `ConditionalOrderEvaluator`가 `@EventListener` + `@Async("conditionalOrderExecutor")`로 구독한다 — `AlertEvaluator`(`backend/worker`)가 `TickProcessedEvent`를 구독하는 것과 정확히 같은 모양이다.

4. **원자적 발동(atomic trigger)**: 평가기는 JPA 엔티티 로딩 없이 `jdbc.update("UPDATE conditional_orders SET status='TRIGGERED' WHERE id=? AND status='ACTIVE'")`를 먼저 실행하고, **영향받은 행 수가 1일 때만** 다음 단계(실제 주문 제출)로 진행한다. 같은 틱이 여러 커넥션에서 동시에 들어오거나, 재시도/재연결로 같은 이벤트가 중복 발행돼도 정확히 한 번만 발동하게 만드는 유일한 방법이다 — `AlertEvaluator`의 Redis `setIfAbsent` 쿨다운과 같은 목적을 DB 레벨에서 구현한 것이다(여기선 Redis 캐시가 아니라 "실제 돈이 나가는" 행동이라 DB 트랜잭션의 원자성이 필요하다).

5. **발동 시 리스크 게이트는 그대로 재사용, 재시도는 하지 않는다**: 발동 → `BrokerageService.submitOrder(userId, request)` 호출 → 그 안의 ADR-025 리스크 게이트가 평소와 동일하게 평가한다. 거부되면 `conditional_orders.status='FAILED'`로 기록하고 끝 — 자동 재시도하지 않는다. 조건부 주문이 실패를 반복 재시도하면 "가격이 조건을 충족하는 동안 매 틱마다 브로커에 주문을 난사"하는 사고로 이어질 수 있다. 실패하면 사용자가 직접 다시 등록해야 한다.

6. **OCO는 `oco_group_id`(UUID, nullable) 컬럼으로 페어링한다.** 한쪽이 발동해 `EXECUTED` 또는 `FAILED`가 되면, 평가기가 같은 그룹의 `ACTIVE` 형제를 찾아 `CANCELLED`로 전환한다(브로커 호출 없음 — 애초에 제출된 적 없는 대기 상태이므로 로컬 취소만으로 충분).

7. **OTO는 이번 범위에서 완전히 제외한다.** 체결 이벤트가 트리거라 가격 틱 이벤트(`MarketTickReceivedEvent`)와는 다른 소스가 필요하고(`BrokerageOrder`가 FILLED로 바뀌는 시점을 관찰해야 함), 지금 만드는 평가기 구조를 그대로 재사용할 수 없다.

## Reasons

- **브로커 네이티브를 쓰지 않는 이유**: Context에서 이미 설명 — 검증 안 된 서버측 자동 발동이 ADR-025 리스크 게이트를 우회할 수 있다는 게 이 세션이 절대 받아들일 수 없는 리스크다. 반대로 monticker 자체 감시는 발동 시점이 정확히 `submitOrder()`를 다시 타므로, 수동 주문과 조건부 주문이 리스크 정책 관점에서 완전히 동일하게 취급된다.
- **`brokerage_orders`를 확장하지 않고 새 테이블을 만든 이유**: 기존 CHECK 제약을 깨는 마이그레이션(order_type에 STOP_LOSS 추가 등)은 "제출된 적 없는 주문"과 "제출된 주문"이라는 서로 다른 생명주기를 한 테이블에 억지로 합치는 셈이라 오히려 더 복잡해진다. 발동 시 기존 테이블에 정상적인 MARKET/LIMIT 행을 만드는 지금 방식이 기존 코드(취소/체결 동기화 등)를 전혀 건드리지 않는다.
- **DB UPDATE 기반 원자적 발동**: JPA `@Version` 낙관적 락도 대안이었지만, 평가기는 애초에 엔티티를 로드할 필요가 없다(트리거 여부 판단에 필요한 컬럼이 적다) — `AlertEvaluator`가 이미 이 모듈에서 raw JDBC로 hot path를 구현한 선례를 그대로 따른다.
- **재시도하지 않는 이유**: 조건부 주문은 "가격이 조건을 넘은 순간 이후 계속 그 조건을 만족하는 상태"가 흔하다(예: 손절가 아래로 떨어진 채 계속 하락) — 실패 시 재시도를 허용하면 같은 종목에 매 틱마다 실패하는 주문 요청이 반복 발사될 수 있다. 1회 실패 → FAILED로 확정하고 사용자가 판단해서 재등록하게 한다.

## Consequences

- `backend/api`가 `market.ticks`에 대해 두 개의 소비자를 갖게 된다(`PriceBroadcaster`용 브로드캐스트, 그리고 이번 `ConditionalOrderEvaluator`) — 둘 다 같은 `MarketTickBroadcastConsumer.onTick()` 안에서 파생되므로 Kafka 컨슈머 자체는 하나 그대로다.
- 조건부 주문은 종목당 하나씩 걸려있는 게 아니라 여러 개가 겹칠 수 있다(같은 종목에 대해 여러 사용자가 서로 다른 트리거가를 등록) — 매 틱마다 해당 stockId의 ACTIVE 조건부 주문 전부를 조회해 평가한다(`AlertEvaluator`의 `fetchRulesForStock`과 동일 패턴).
- OCO 미체결 형제를 취소할 때 브로커 호출이 필요 없다는 게 이번 설계의 이점이지만, 반대로 말하면 조건부 주문 자체는 브로커에 전혀 알려지지 않은 순수 monticker 내부 상태다 — 사용자가 HTS/앱을 통해 그 계좌에서 직접 주문을 내면 monticker의 조건부 주문과 아무 조율 없이 독립적으로 존재한다(같은 계좌를 여러 채널로 쓰면 의도치 않은 중복 매매 리스크가 있다는 걸 사용자가 인지해야 한다 — UI 안내 문구로 명시).
- OTO는 여전히 미지원이다.

## Revisit When

- OTO(주문 체결 트리거)를 만들 때 — `BrokerageOrder.fill()` 시점을 관찰하는 별도 이벤트/리스너가 필요하며, 이번 `ConditionalOrderEvaluator`와는 다른 트리거 소스를 갖는 새 컴포넌트가 된다.
- Toss의 `/api/v1/conditional-orders`를 실제로 검증해 서버측 보유·발동 여부가 명확해지면 — 그래도 ADR-025 우회 문제가 있어 채택 여부는 별도로 재논의해야 한다(검증됐다고 자동으로 채택하지 않는다).
- 조건부 주문 수가 늘어나 매 틱 evaluator의 DB 조회가 병목이 되면 — `AlertEvaluator`처럼 이미 stockId로 필터링돼 있어 당장은 아니지만, 인메모리 캐시(주기적 갱신)로 전환할 수 있다.
- 동일 계좌를 다른 채널(HTS 등)로도 거래하는 사용자를 위한 조율(주문 상태 실시간 동기화 등)이 필요해질 때.
