# ADR-074: 모의투자 지정가 주문 — 파사드에 LIMIT을 열고, 미체결은 DB 스위퍼가 체결한다

## Status
Accepted

## Context

종목 화면 주문 패널의 "지정가"는 비활성이었다. 모의투자 API(`/api/paper/buy|sell`)는 시장가만 받는다. 로드맵에는
"지정가 매칭엔진(`/api/matching`)은 별도 가상계좌라 통합 방식을 정하는 ADR이 필요"라고 적혀 있었지만, 확인해 보니
[ADR-047](047-single-execution-path-for-paper-account.md) 이후 둘은 **이미 같은 계좌**(`paper_accounts`)다. 매칭 엔진이 유일한
체결 경로이고 paper 모듈은 체결 이벤트로 계좌 기록을 만든다. 남은 문제는 둘이었다.

1. **미체결 LIMIT이 영원히 체결되지 않는다.** 사가는 제출 순간 교차하면 즉시 체결하고, 아니면 pod 힙의 호가창
   (`MatchingOrderBookService`)에 넣기만 한다. 그 호가창의 매칭 결과는 버려진다(ADR-048 주석) — pod마다 갈라져 있어
   체결 근거가 될 수 없기 때문이다. 결과적으로 "지정가 9만 원에 매수"는 시세가 9만 원 아래로 내려가도 그대로 남는다.
2. **미체결 SELL이 보유 수량을 예약하지 않는다.** 보유 10주로 10주 매도 지정가를 걸어 둔 채 또 10주를 팔 수 있었다.
   둘 다 체결되면 공매도다(ADR-047 §5 위반).

### 후보

- **A) 종목 화면이 `/api/matching/orders`를 직접 부른다.** 서버 변경이 가장 적다. 하지만 응답에 계좌 기록의
  `tradeId`가 없어 영수증·감정 태그 흐름(포트폴리오와 같은 UX)이 끊긴다.
- **B) paper 파사드에 `orderType`·`limitPrice`를 연다(ADR-047 Revisit When 그대로).** `matching::submit`에
  `submitLimit`을 추가한다.
- 미체결 체결 방식:
  - **B1) 힙 호가창에서 체결** — 단일 라이터(scale-out-plan §6.8) 없이는 pod마다 결과가 다르다. 기각.
  - **B2) 가격 이벤트(틱) 소비자가 체결** — 틱마다 미체결을 조회하면 비용이 틱 수에 비례하고, 틱 컨슈머는 파티션별로
    여러 pod에 흩어져 결국 행 락이 필요하다. 이득 없이 결합만 는다.
  - **B3) 주기 스위퍼가 DB의 `orders` × 최신 `candles_1m` 종가로 판정·체결** — 행 락(`FOR UPDATE SKIP LOCKED`)으로
    다중 pod에서도 한 번만 체결된다.

## Decision

**B + B3.**

```
POST /api/paper/orders {stockId, side, orderType: MARKET|LIMIT, quantity, limitPrice?}
  └─ PaperTradingService.placeOrder
       ├─ MARKET → 기존 submitMarket (응답 형태만 공용)
       └─ LIMIT  → matching::submit OrderSubmitter.submitLimit  (@RiskChecked — limitPrice가 예상 가격)
                     └─ OrderSagaOrchestrator: 교차하면 즉시 체결, 아니면 PENDING (BUY는 limit×qty 예약)

LimitOrderSweeper (@Scheduled 3s, app.matching.limit-sweep.*)
  SELECT 미체결 LIMIT ⋈ LATERAL 최신 candles_1m 종가  WHERE 교차   (락 없이 후보만)
  └─ 주문마다 LimitOrderFiller.fillIfCrossed(id)  — 트랜잭션 1개
       SELECT … FOR UPDATE SKIP LOCKED  → 상태·교차 재확인
       → fills INSERT · order FILLED · BUY 예약 차액 환불 / SELL 대금 입금
       → OrderFilledEvent (동기) → paper.PaperExecutionListener: paper_trades·포지션·T+2·원장(ADR-047과 동일)
```

1. **체결가는 그 순간의 최신 종가**다(BUY는 ≤ 지정가, SELL은 ≥ 지정가). 사가의 즉시 체결과 같은 규칙이다.
2. **리스크 게이트는 제출 시점과, BUY는 체결 직전에 한 번 더.** 제출 시점 판정은 미체결 지정가 매수(잔량 × 지정가)를 진행 중 노출로 센다.
   체결 직전 재판정은 걸어 둔 뒤 한도를 조였거나 일간 손실 한도에 이른 경우를 막는다(시간당 주문 수는 제출 때 이미 셌으므로 제외).
   차단되면 사용자 취소와 같은 경로로 취소·환불한다. 실브로커는 접수 뒤 체결을 우리가 통제할 수 없어 접수 시점에만 판정한다.
   이 PR 안에서 보안 리뷰로 바뀐 결정이다 — 경위는 아래 Note.
3. **매도 가능 수량 = 보유 − 미체결 SELL 잔량.** 사가가 포지션 행을 `FOR UPDATE`로 잡고 판정해 같은 종목의 동시 매도
   제출을 직렬화한다. 스위퍼는 체결 직전에 포지션을 다시 확인하고, 없으면 체결 대신 취소(사유 기록)한다.
4. **취소와 체결의 경합**: 사용자 취소(`findWithLockById`, FOR UPDATE)와 스위퍼(SKIP LOCKED)는 같은 행 락으로
   직렬화된다. 락을 얻은 쪽이 상태를 다시 보므로 "환불 + 체결"이 함께 일어나지 않는다.
5. 미체결 주문의 **조회·취소는 기존 `/api/matching/orders`**(같은 계좌)를 쓴다. 파사드는 제출만 맡는다.
6. 지정가는 0보다 크고 소수점 4자리 이내(`orders.limit_price NUMERIC(18,4)`). 호가 단위 검증은 모의투자에 두지 않는다
   — 실거래 호가 단위 검증은 brokerage의 별도 과제다.

## Reasons

- **A보다 B**: 영수증·감정 태그·체결 직후 계좌 반영이 시장가와 같은 흐름이다. 프론트가 엔진의 내부 응답 형태를
  알 필요가 없다.
- **B3**: 다중 pod에서 정합성을 행 락 하나로 얻는다. 비용은 `미체결 LIMIT 수 × LATERAL 1행`이고, 미체결은 사용자당
  소수라 3초 주기로 충분하다. 틱 경로(B2)를 건드리지 않아 시세 파이프라인의 처리량과 분리된다.
- 실브로커 경로와 무관하다 — 이 스위퍼는 `orders`(모의 매칭 엔진)만 본다. `brokerage_orders`는 증권사가 체결한다.

## Consequences

- 체결 지연이 최대 스위퍼 주기(기본 3초) + 캔들 갱신 지연이다. 모의투자로는 충분하다.
- 1분봉 종가만 보므로 분 안에서 잠깐 교차했다가 돌아온 가격은 놓친다(고가·저가로 판정하면 "체결됐어야 할"
  주문을 잡지만 체결가가 비현실적이 된다). 의도적으로 종가 기준을 택했다.
- 힙 호가창은 표시용 사본으로 남는다. 다른 pod의 사본에는 체결된 주문이 남아 있을 수 있다(ADR-048과 같은 한계).
- 장 운영 시간을 보지 않는다 — 캔들이 갱신되지 않으면 판정도 바뀌지 않으므로 장외에는 자연히 멈춘다.
- 부분 체결은 없다(전량 체결). 모의투자에 유동성 모형이 없기 때문이다.

## Note (2026-10 보안 리뷰 후속 — 미체결 지정가 매수와 리스크 한도)

리뷰에서 **미체결 모의 지정가 BUY가 집중도·섹터·보유 종목 수·일간 손실 한도를 우회**한다는 점이 확인됐다(HIGH).

- 모의계좌 스냅샷(`RiskRuleQueryService.paperSnapshot`)에 `pendingBuys`가 비어 있었다. 실거래는 ADR-058의 `PendingBuyQuery`로
  진행 중 매수를 세는데, 모의투자는 "사가가 원자적으로 바꾼다"는 전제로 비워 두었다 — 지정가가 생긴 뒤로는 틀린 전제다.
  한도 30%에 25%짜리 지정가 매수를 두 건 걸면 각각 통과했고, 신규 종목 지정가를 여러 건 걸어 종목 수 한도도 넘었다.
- 제출 시점 판정(Decision 2)만 있어서, 그 사이 다른 매수가 체결되거나 손실이 커져도 미체결 주문은 그대로 체결됐다.

변경:

1. **스냅샷에 미체결 매수를 넣는다.** `orders`의 `side='BUY' AND status IN ('PENDING','PARTIALLY_FILLED')` 잔량을 종목별로
   `pendingBuys`에 더한다(ADR-058과 같은 의미 — 노출을 늘리는 쪽만). 예약금(`limit_price × 잔량`)은 제출 때 이미 현금에서
   빠졌으므로 `PortfolioSnapshot.reservedCash`로 분모(`현금 + 예약금 + Σ보유`)와 일간 손실 기준 금액에 되돌린다 —
   그렇지 않으면 같은 노출이 분자에 더해지고 분모에서 빠져 이중으로 불리해진다(지갑 화면의 총자산과 같은 정의).
   제출 게이트(@RiskChecked)·조건부 주문 발동(`PaperConditionalOrderTrigger` → `OrderSubmitter`)·한도 근접 경고
   (`PaperRiskUsage`)가 모두 이 스냅샷을 쓰므로 함께 바뀐다. risk 모듈은 `orders`를 SQL로만 읽어 모듈 경계는 그대로다.
2. **체결 직전에 BUY를 다시 판정한다.** `LimitOrderFiller`가 행 락(`FOR UPDATE SKIP LOCKED`)을 잡은 같은 트랜잭션에서
   `RiskCheckerService.checkPaperFill`(잔량 × 체결가)을 부른다. 이 주문 자신은 대기 매수 수량에서 빼고(이번 수량으로 한 번만
   센다) 예약금은 분모에 남긴다. 빈도 한도(TradingFrequencyRule)는 제출 때 이미 센 주문이라 다시 걸지 않는다.
   막히면 체결하지 않고 **취소 + 예약금 전액 환불 + `OrderCancelledEvent`** — 사용자 취소와 같은 상태 전이·이벤트이며
   `reject_reason`에 차단 규칙을 남긴다. 판정은 `risk_check_logs`에 감사 기록된다.

3. **오래된 봉으로 체결하지 않는다.** 판정 기준인 최신 1분봉이 5분(`CandleFreshness.MAX_AGE`)보다 오래됐으면 그 주기는
   건너뛴다(스위퍼 후보 조회와 `LimitOrderFiller` 둘 다). 시세 수집이 끊긴 동안 몇 시간 전 종가로 체결되던 것을 막는다.
   조건부 주문 발동(ADR-075)도 같은 기준을 쓴다.
4. **제출 시점의 즉시 체결도 같은 기준이다**(2026-10 보안 리뷰 후속). `OrderSagaOrchestrator`는 최신 1분봉 종가를 나이 검사
   없이 체결가로 썼다 — 시세가 끊긴 뒤의 시장가(Watch Rule 자동 주문 포함)가 몇 시간 전 가격으로 체결됐다. 이제
   - **시장가**: 봉이 `CandleFreshness.MAX_AGE`보다 오래됐으면 `IllegalStateException`(409, "시세가 5분 넘게 갱신되지 않아…")으로
     거부한다. 검사는 현금 예약(STEP 2)·보유 수량 잠금 **앞**이라 예약도 환불도 생기지 않는다. 그 뒤 단계의 실패는 사가 전체가
     한 트랜잭션이라 예약과 보상이 함께 롤백된다 — 현금은 정확히 한 번(또는 0번) 움직인다.
   - **지정가**: 거부하지 않는다. 교차해도 즉시 체결하지 않고 미체결(PENDING)로 접수한다 — 시세가 신선해지면 스위퍼가 체결한다.
   - 봉이 아예 없으면 예전처럼 둘 다 거부("현재가 조회 불가").

트레이드오프: 장 중 손실이 커지면 걸어 둔 지정가 매수가 체결 시점에 취소될 수 있다. 실브로커는 접수 후 판정하지 않으므로
모의투자가 더 엄격하다 — 한도는 노출을 늘리는 순간에 지켜져야 한다는 쪽을 택했다.

## Revisit When

- 단일 라이터 매칭(scale-out-plan §6.8)이 생겨 호가창이 체결 근거가 될 수 있을 때 — 스위퍼를 대체한다.
- 미체결 지정가가 많아져(수만 건) 3초 스위프의 LATERAL 조회가 부담될 때 — 종목별 최신가 테이블과 조인한다.
- 모의투자에도 호가 단위·가격제한폭을 강제하기로 할 때.
