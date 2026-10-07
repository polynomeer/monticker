# ADR-085: 모의 주문의 진입 출처는 서버 제출 경로가 정해 주문·체결에 저장한다

## Status
Accepted

## Context

모의 체결(`paper_trades`)에는 **왜 이 주문이 나갔는지**가 남지 않았다. 디자인 시안의 여러 칸이 이 정보를 요구한다
([design-rollout-plan.md](../design-rollout-plan.md) P2): 하단 보유 종목 "진입 경로", /wallet 원장 행의 출처,
/watch-rules "규칙 경유 손익", /wallet/replay "계획 준수율"·"계획 외 주문".

지금까지 거래 내역의 "경로"는 매칭 주문의 멱등 키 접두사에서 추정했다(`WR:{ruleId}:…` = Watch Rule(ADR-051),
`PCO:{id}` = 조건부 주문(ADR-075), 그 외 = 직접). 문제는 두 가지다.

1. **위조 가능.** `/api/matching/orders`가 요청 본문을 내부 DTO `SubmitOrderRequest`로 바로 바인딩해, 클라이언트가
   `"idempotencyKey": "WR:5:x"`를 보내면 직접 낸 주문이 Watch Rule 주문으로 보였다. "규칙 경유 손익"·"계획 준수율"처럼
   행동을 평가하는 숫자가 사용자 입력으로 조작된다. (같은 경로로 서버 내부 멱등 키 공간에 값을 심을 수도 있었다.)
2. **문자열 규약 의존.** 키 형식을 바꾸면 과거 거래의 경로가 조용히 "직접"으로 바뀐다. 출처별 집계는 `LIKE 'WR:%'` 같은
   문자열 조건이 되고 인덱스를 타기 어렵다.

고려한 대안:

- **A. 멱등 키 추정 유지 + 컨트롤러에서 키만 제거** — 위조는 막지만 2번이 남고, 멱등 키가 없는 출처(예: 앞으로의 전략
  직접 주문)를 표현할 수 없다.
- **B. 체결 시점에 Watch Rule 실행 기록·조건부 주문 테이블을 조인해 추정** — 서버 링크라 위조는 안 되지만, 읽을 때마다
  여러 모듈 테이블을 조인해야 하고 `paper` 모듈이 `watchrule`의 테이블을 알아야 한다.
- **C. 제출 경로가 출처를 명시하고 주문·체결 행에 저장** — 채택.

## Decision

### 출처 모델

```kotlin
// matching::submit (OrderSubmitter와 같은 공개 인터페이스)
enum class OrderOriginType { MANUAL, WATCH_RULE, CONDITIONAL, STRATEGY }
data class OrderOrigin(val type: OrderOriginType, val ref: Long? = null)  // MANUAL은 ref 없음, 나머지는 필수
```

| origin | 누가 정하나 | origin_ref |
|---|---|---|
| `MANUAL` | `PaperTradingService`(화면 주문), `MatchingController` | 없음 |
| `WATCH_RULE` | `WatchRuleExecutor` | `watch_rules.id` (전략 신호 발동도 규칙을 거치므로 여기) |
| `CONDITIONAL` | `PaperConditionalOrderFirer` | `paper_conditional_orders.id` |
| `STRATEGY` | **아직 없음** — 전략이 규칙 없이 직접 모의 주문을 내는 경로가 생기면 쓴다 | 룰셋 id |

리밸런싱은 실전 계좌(ADR-034)에만 있어 모의 출처에 넣지 않았다. 생기면 값과 CHECK를 함께 추가한다.

- `OrderSubmitter.submitMarket/submitLimit`의 `origin`은 **기본값 없는 필수 인자**다. 새 서버 경로가 출처를 빠뜨리면
  컴파일이 깨진다. `SubmitOrderRequest.origin`도 같다.
- 사가가 `orders.origin/origin_ref`에 저장하고 `OrderFilledEvent`에 싣는다. `PaperExecutionListener`가 그 값을
  `paper_trades.origin/origin_ref`로 옮긴다. 미체결 지정가가 나중에 스위퍼로 체결될 때는 주문 행에서 읽는다.
- `/api/matching/orders`는 전용 DTO `MatchingOrderRequest`(종목·방향·유형·수량·지정가만)를 받는다. 멱등 키와 출처는
  요청 본문으로 정할 수 없다(알 수 없는 필드는 무시된다).
- `OrderFilledEvent`의 새 필드는 nullable이다. 필드 추가 전에 직렬화된 아웃박스 이벤트를 다시 읽어도 깨지지 않는다.
- FK를 걸지 않는다. 규칙·조건부 주문을 지워도 과거 체결의 출처 기록은 남아야 한다(원장과 같은 이유, ADR-013).

### 마이그레이션

- **V81**: `orders`·`paper_trades`에 `origin VARCHAR(20)`, `origin_ref BIGINT`, 값 CHECK, 출처 집계용 부분 인덱스.
- **V82**: 백필. **서버가 남긴 링크만** 근거로 쓴다. 멱등 키 접두사는 위조됐을 수 있어 쓰지 않는다.
  1. `watch_rule_executions.order_id`(EXECUTED, 같은 사용자)가 가리키는 주문 → `WATCH_RULE`, ref = 규칙 id
  2. `paper_conditional_orders.executed_order_id`(같은 사용자) → `CONDITIONAL`, ref = 조건부 주문 id
  3. 멱등 키가 없는 주문 → `MANUAL`. 서버 내부 경로(규칙·조건부)는 처음부터 항상 키를 붙였다(ADR-051/075)
  4. 체결 기록은 `fill_id → fills.order_id → orders`의 출처를 복사
  5. `fill_id`가 없는 체결(ADR-047 이전 구 페이퍼 경로, 화면 주문뿐이었다) → `MANUAL`
  6. 나머지(키는 있는데 링크가 없는 주문 등)는 **NULL**. 화면은 "—"로 보이고 출처별 집계·계획 준수율 분모에서 빠진다.

  모든 문장이 `origin IS NULL` 행만 건드려 다시 실행해도 결과가 같다(통합 테스트가 재실행으로 확인한다).
  같은 파일에서 감정 태그 `OTHER` + 메모가 정확히 "계획대로"인 행을 `PLANNED`(메모 비움)로 옮긴다. 다른 내용을 덧붙인
  메모는 의도를 추측하지 않고 그대로 둔다. `order_emotion_tags.emotion`은 CHECK 없는 VARCHAR(30)이라 스키마 변경은 없다.

### 감정 태그

`EmotionType`에 `PLANNED`("계획대로")·`IMPATIENT`("조급함")를 추가했다. 행동 점수에서 `IMPATIENT`는 `ANXIOUS`와 같이
"감정적 충동"으로 본다: "충동 거래 없음 +10"만 빠지고 FOMO 같은 감점은 없다.

### 읽기 모델

- 보유 종목: 종목별 **가장 최근 매수 체결**의 출처(`entryOrigin`, 한 쿼리 `DISTINCT ON`). 최근 이벤트는 event 모듈의
  `GET /api/events/latest?stockIds=`(종목별 LATERAL 1건)로 웹이 한 번에 읽는다 — paper가 event를 의존하지 않는다.
- 거래 내역: `source`(= origin)·`originRef`와 감정 태그를 한 쿼리로 싣는다. 거래마다 감정 API를 부르던 N+1을 없앴다.
  감정 태그 테이블은 wallet 소유지만 읽기 전용 조인이며, 태그 소유자 = 거래 소유자 조건을 건다.
- 원장: 체결 행(`FILL`·`SETTLEMENT`)에 거래의 출처를 붙인다(거래 일괄 조회 1번). ADR-047 이전 원장은 `paper_trade_id`에
  `fills.id`가 있어(V43) id가 겹칠 수 있으므로 같은 사용자·같은 종목일 때만 붙인다.
- **출처별 실현 손익** `GET /api/paper/pnl/by-origin?origin=WATCH_RULE` — 출처 ref별(규칙별)·합계.
  - **매도 체결의 출처로 귀속**한다. "규칙 #3 경유 손익" = 규칙 #3이 낸 매도 체결의 실현 손익 합. 규칙이 사고 사용자가
    직접 팔았다면 그 손익은 MANUAL 쪽이다. 로트(매수 건) 추적은 하지 않는다.
  - 실현 손익 = 매도금액 − 수량 × 그 시점 평균 매수단가, **이동평균법**(`PortfolioPositionProjection`과 같은 규칙: 매도는
    평균단가를 바꾸지 않고, 전량 매도 후 다시 사면 새 평균). 수수료·세금 전.
  - 리플레이의 매도 손익도 같은 계산을 쓴다. 이전 리플레이는 그 종목 전체 매수가의 **단순 평균**(수량 가중 없음, 매도 이후
    매수까지 포함)이었다.

### "계획된 주문"의 정의 (리플레이)

```
planned = origin ∈ {WATCH_RULE, STRATEGY, CONDITIONAL}  또는  감정 태그 = PLANNED
계획 외 = origin = MANUAL 이고 PLANNED 태그가 없음
판정 불가 = origin 이 NULL 이고 PLANNED 태그도 없음 → 분모에서 제외
계획 준수율 = 계획된 주문 수 ÷ 판정 가능한 주문 수 × 100   (판정 가능한 주문이 없으면 null → "—")
```

규칙·조건부·전략 주문은 사용자가 **미리 정한 조건**이 낸 주문이라 계획된 것으로 본다. 직접 주문도 사용자가 "계획대로"로
태그하면 계획된 주문이다. 화면 툴팁(`PLANNED_DEFINITION`)에 같은 정의를 쓰고, 이 숫자가 기록을 돌아보기 위한 지표이며
투자 판단의 기준이 아니라고 적는다(투자 권유로 읽히는 문구를 쓰지 않는다).

## Reasons

- 출처를 정하는 쪽이 **주문을 내는 서버 코드**뿐이라 클라이언트가 조작할 수 없다. 출처가 행에 있으니 집계가 컬럼 조건과
  인덱스로 끝나고, 키 형식 변경과 무관하다.
- 필수 인자라 출처 누락이 런타임 데이터 품질 문제가 아니라 컴파일 오류가 된다.
- 백필을 서버 링크로만 하면 과거 데이터에도 위조된 키가 섞이지 않는다. 증명할 수 없는 행은 NULL로 두고 화면이 "—"를
  보인다(지어낸 숫자를 보여 주지 않는다 — design-rollout-plan 원칙).
- 손익 계산을 보유 화면과 같은 이동평균 규칙 한 곳(`RealizedPnlCalculator`)에 모아, 리플레이·규칙 손익·보유 평균단가가
  서로 다른 숫자를 내지 않는다.

## Consequences

- `OrderSubmitter` 시그니처가 바뀌어 호출부·목·페이크를 모두 고쳤다. 새 제출 경로는 출처를 반드시 정해야 한다.
- `paper_trades.origin`은 `orders.origin`의 사본(비정규화)이다. 체결 후 주문 출처를 바꾸는 경로는 없으므로 어긋나지 않는다.
- 규칙 손익은 매도 귀속이라 "규칙이 산 종목의 손익"과 다르다. 매수 전용 규칙의 손익은 0으로 보이고(매도 없음 → "—"),
  화면 툴팁이 이를 설명한다.
- 출처별 손익은 해당 종목들의 전체 체결을 읽어 메모리에서 이동평균을 계산한다. 사용자당 체결 수가 매우 커지면 비용이 늘어난다.
- 감정 태그 조인으로 `paper`의 내역 쿼리가 wallet 테이블 이름을 안다(읽기 전용). 태그 테이블 구조를 바꾸면 함께 고쳐야 한다.
- `/api/matching/orders`가 `idempotencyKey`를 더는 받지 않는다. 웹은 이 필드를 보낸 적이 없다(사용자 경로의 중복 방지는
  ADR-007의 `X-Idempotency-Key` 헤더가 맡는다).

## Revisit When

- 전략이 규칙 없이 직접 모의 주문을 내는 경로(STRATEGY)나 모의 리밸런싱이 생길 때 — 출처 값·CHECK·계획 정의를 함께 갱신한다.
- 매수 로트 단위로 "규칙이 연 포지션의 손익"을 보여 달라는 요구가 생길 때(FIFO/로트 추적 필요).
- 실전 계좌 주문에도 같은 출처를 남기기로 할 때(`brokerage_orders`는 이 ADR 범위 밖).
- 사용자당 체결 수가 커져 출처별 손익 계산이 느려질 때 — 실현 손익을 체결 시점에 저장하는 방식으로 바꾼다.
