# ADR-034: 리밸런싱 실행 자동화 (실브로커리지, 수동 실행)

## Status
Accepted

## Context

[engineering-backlog.md](../engineering-backlog.md) §3: `PortfolioOptimizerService`(`backend/api/.../analytics/application/`)가 목표 비중 계산까지는 이미 한다 — 실제로는 **평균-분산(Markowitz) 최소분산 최적화**로, 호출자가 넘긴 임의의 종목 리스트에 대해 목표 비중을 계산해 돌려주는 순수 "what-if" 계산기다(`GET /api/analytics/portfolio/optimize`, 이미 `apps/web`의 분석 탭에 연결돼 실제로 쓰이는 중). **보유 현황을 전혀 읽지 않는다** — `stockIds`를 호출자가 그때그때 넘기고, 결과(`weightsJson`)는 `portfolio_optimizations` 테이블에 기록만 될 뿐 특정 계좌와 연결되지 않는다.

즉 "목표 비중 계산은 이미 있다"는 설명과 달리, 실제로 없는 건 diff 계산·실행 로직뿐이 아니라 **"이 계좌의 목표 비중"이라는 영속 개념 자체**다 — 이번 ADR에서 새로 만들어야 한다.

**조사 결과 보유 포트폴리오가 두 종류이고 안전성이 다르다**:
- **실브로커리지(BYOK)**: `BrokerageService.submitOrder(userId, request)`가 리스크 게이트(`RiskCheckerService.checkBrokerageOrder`, ADR-025)를 항상 내장하고 있다. [ADR-032](032-conditional-orders.md)의 `ConditionalOrderEvaluator`가 이미 이 경로로 안전하게 우회 없이 발동하는 전례가 있다.
- **모의투자**: 주문 경로가 **둘로 갈린다**. `PaperController`→`PaperTradingService.buy/sell`은 리스크 게이트가 전혀 없고(즉시 체결, 호가창 없음), `MatchingController`→`MatchingService.submitOrderChecked`(`@RiskChecked` AOP)→`OrderSagaOrchestrator`만 리스크 게이트를 거친다. 잘못된 경로로 자동화를 짜면 안전장치가 전혀 없는 채로 실행된다.

**사용자 결정** (AskUserQuestion):
1. 1라운드는 **실브로커리지(BYOK)만** — 리스크 게이트가 이미 단일 진입점(`submitOrder`)에 내장돼 있어 명확하다. 모의투자는 두 경로 중 안전한 쪽(`OrderSagaOrchestrator`)만 쓰도록 별도 설계가 필요해 다음 라운드로 미룬다.
2. **수동 버튼만** — 실제 돈이 움직이는 작업이라 [ADR-032](032-conditional-orders.md)와 같은 원칙(자동 실행에 신중)을 적용한다. 사용자가 diff(계획)를 직접 보고 승인해야 실행된다. 스케줄 기반 자동 실행은 범위 밖.

## Decision

### 1. 새 도메인: 목표 비중은 계좌당 하나, JSON으로 저장

`rebalance_targets` 테이블 — 계좌(`brokerage_accounts`)당 활성 목표 하나(유니크 인덱스로 강제). `weights_json`(symbol→weight 맵)으로 저장한다 — `PortfolioOptimization.weightsJson`이 이미 이 저장소에서 쓰는 관례를 그대로 따른다. 목표 비중의 합이 1.0을 넘지 않으면(검증) 나머지는 암묵적 현금 비중으로 취급한다.

```kotlin
data class RebalanceTarget(
    val id: Long, val userId: Long, val accountId: Long,
    val weights: Map<String, BigDecimal>,   // symbol -> weight
    val thresholdPct: BigDecimal,           // 기본 5%
    val source: String,                     // "OPTIMIZER" | "MANUAL"
)
```

목표는 `PortfolioOptimizerService.optimize()` 결과를 그대로 저장(`source=OPTIMIZER`)하거나, 사용자가 직접 종목·비중을 입력(`source=MANUAL`)해서 저장할 수 있다 — 기존 최적화 계산기를 갈아엎지 않고 그 위에 "저장 후 계좌에 연결"하는 계층만 얹는다.

### 2. diff는 실행 시점에 매번 새로 계산한다 (미리 저장한 계획을 재사용하지 않는다)

`GET /api/rebalance/preview`(조회, 부작용 없음)와 `POST /api/rebalance/execute`(실행) 둘 다 **그 순간의** `BrokerageService.getBalance()`(실시간 브로커 조회)와 저장된 목표 비중으로 diff를 새로 계산한다. 미리보기와 실행 사이에 가격·보유가 바뀔 수 있으므로, 실행이 미리보기 시점의 스냅샷을 그대로 재사용하면 오래된 계획으로 주문이 나갈 위험이 있다 — "발동 시점에만 유효한 최신 데이터로 판단한다"는 [ADR-032](032-conditional-orders.md)의 원칙과 동일하다.

```
currentWeight(종목) = 보유평가금액 / balance.totalEvaluated   (미보유 종목은 0)
diffPct(종목)       = targetWeight - currentWeight
threshold 미만이면 실행 대상에서 제외
diffPct > 0 → BUY, diffPct < 0 → SELL
quantity = floor(|diffPct| × totalEvaluated / 현재가)   (SELL은 보유 수량을 넘지 않게 cap)
```

목표에 없지만 보유 중인 종목은 targetWeight=0으로 취급해 자동으로 전량 매도 후보에 포함된다.

### 3. 실행은 각 leg를 순차적으로 `BrokerageService.submitOrder()`에 그대로 위임한다

리스크 게이트를 별도로 호출하거나 우회하지 않는다 — [ADR-032](032-conditional-orders.md)의 `ConditionalOrderEvaluator`와 정확히 같은 패턴이다. **SELL을 먼저, 그다음 BUY**(현금 확보 후 매수) 순서로, 각 leg는 이전 leg의 체결이 반영된 이후 상태에서 리스크를 재평가받는다(순차 실행이라 병렬 실행 시 생기는 동시성 문제도 없다). MARKET 주문만 지원한다(LIMIT 없음 — 지금 즉시 판단해 실행하는 작업이라 지정가로 미체결 상태를 남길 이유가 없다).

한 leg가 실패(리스크 거부 또는 증권사 거부)해도 **나머지 leg는 계속 진행한다** — 리밸런싱은 일부만 체결돼도 의미가 있다(전부 아니면 전무로 묶을 이유가 없다). 실행 결과(`rebalance_executions`/`rebalance_execution_legs`)에 leg별 성공/실패를 개별 기록한다.

### 4. 스케줄러는 만들지 않는다 — 수동 트리거 REST 엔드포인트만

`POST /api/rebalance/execute`가 유일한 진입점이다. 기존 `ForwardTestScheduler`(ADR-024, `@Scheduled` cron) 패턴을 참고할 수 있게 조사해뒀지만, 이번 라운드는 사용자 결정에 따라 쓰지 않는다.

## Reasons

- **실브로커리지만, 모의투자 제외**: 모의투자의 두 경로 중 리스크 게이트가 없는 쪽(`PaperTradingService.buy/sell`)을 실수로 타면 조건부 주문(ADR-032)이 막으려 했던 것과 똑같은 우회가 벌어진다. 지금 시점에 안전하게 짤 수 있는 범위(실브로커리지)만 먼저 만들고, 모의투자는 `MatchingService.submitOrderChecked` 경로로 한정하는 걸 별도로 설계한 뒤 착수한다.
- **diff를 실행 시점에 재계산**: 리밸런싱은 짧게는 몇 분, 길게는 사용자가 미리보기를 띄워두고 고민하는 동안 가격이 움직일 수 있는 작업이다. 저장된 계획을 그대로 실행하면 "미리보기에서 본 것"과 "실제로 나간 주문"이 달라질 수 있다 — 항상 최신 데이터로 다시 계산해서 실행 직전 상태를 반영한다.
- **SELL 먼저, BUY 나중**: 매수 대금을 매도로 확보하는 일반적인 리밸런싱 실행 순서다. 리스크 게이트의 현금 여력 체크와도 자연스럽게 맞는다(매도 전에 매수부터 시도하면 현금 부족으로 리스크 게이트가 막을 수 있다).
- **leg 하나 실패해도 나머지 진행**: 리밸런싱 목적 자체가 "가능한 만큼 목표에 가깝게" 맞추는 것이라, 전부 아니면 전무로 묶으면 오히려 사용자에게 불리하다(하나가 막혔다고 나머지 정상 leg까지 취소할 이유가 없다).
- **스케줄러를 만들지 않는 이유**: 실제 돈이 사용자 승인 없이 움직이는 걸 최소화한다는 이번 세션 전체의 원칙(ADR-025, ADR-032)과 같다. 자동 스케줄이 필요해지면 그건 "이 실행 로직을 스케줄에 태운다"는 훨씬 작은 후속 작업이지, 지금 다시 설계할 필요는 없다.

## Consequences

- 새 테이블 3개(`rebalance_targets`, `rebalance_executions`, `rebalance_execution_legs`) — `brokerage_orders`/`conditional_orders`와 마찬가지로 CHECK 제약으로 상태를 강제한다.
- `PortfolioOptimizerService`는 전혀 수정하지 않는다 — 그 위에 "저장 후 diff 계산" 계층만 얹는다. 기존 `/api/analytics/portfolio/optimize` 엔드포인트·프론트 분석 탭은 영향받지 않는다.
- 모의투자 리밸런싱은 여전히 없다 — 사용자가 모의투자로 먼저 리밸런싱을 연습해볼 수 없다는 뜻이다. 다음 라운드 대상.
- 목표 비중의 합이 1.0 미만이면 나머지는 암묵적 현금 비중으로 해석되는데, 이 규칙이 UI에 명확히 설명되지 않으면 사용자가 "왜 현금이 안 줄어드나"를 오해할 수 있다 — 프론트 문구에서 명시할 것.
- 순차 실행이라 종목 수가 많으면(예: 20종목) 완료까지 브로커 API 호출이 그만큼 이어져 시간이 걸린다 — 동기 HTTP 응답으로 처리하되, 사용자에게 "처리 중" 상태를 보여줘야 한다(각 leg가 실제 네트워크 호출을 거치므로 즉시 끝나지 않는다).

## Revisit When

- 모의투자 리밸런싱이 필요할 때 — `MatchingService.submitOrderChecked`/`OrderSagaOrchestrator` 경로로 한정해 별도 실행기를 만든다(`PaperTradingService.buy/sell` 경로는 리스크 게이트가 없어 재사용 금지).
- 스케줄 기반 자동 실행이 필요할 때 — 이번에 만든 실행 로직(diff 계산 + leg 순차 제출)을 `@Scheduled` 잡에서 그대로 재사용하고 트리거만 추가한다.
- 종목 수가 많아 순차 실행 시간이 사용자 경험에 문제가 될 때 — 비동기 처리(작업 큐 + 진행 상황 폴링)로 전환.
