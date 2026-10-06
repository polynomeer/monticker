# ADR-075: 모의투자 조건부 주문(익절·손절·OCO)과 "체결 시 자동 등록"

## Status
Accepted

## Context

종목 화면의 익절/손절 입력과 주문 패널 "조건부" 탭은 비활성이었다. 조건부 주문은 실전 계좌에만 있다
([ADR-032](032-conditional-orders.md) — `conditional_orders`, 발동 = 실브로커 주문). 모의투자에도 같은 개념이 필요하다.

### 후보

- **A) 실전 `conditional_orders`를 재사용하고 계좌 종류 컬럼을 추가** — 테이블이 `brokerage_accounts`·`brokerage_orders`를
  참조하고, 평가기(`ConditionalOrderEvaluator`)가 실시세 출처 게이트·킬 스위치·브로커 클라이언트를 탄다. 모의 행이
  섞이면 "모의 조건이 실브로커로 갈 수 있는" 경로가 코드 한 줄의 분기에 달린다. 기각 — 실돈 경로와 모의 경로는 테이블부터 갈라야 한다.
- **B) paper 모듈의 별도 테이블 + 발동은 `matching::submit`** — 실브로커 코드와 공유하는 것이 없다.
- 발동 시점:
  - **B1) 틱 이벤트(`MarketTickReceivedEvent`) 구독** — 실전 평가기와 같은 모양. 하지만 paper는 marketdata에 의존하지 않고,
    틱 리스너는 pod마다 돌아 결국 행 락이 필요하다.
  - **B2) 주기 스위퍼** — [ADR-074](074-paper-limit-orders.md)의 지정가 스위퍼와 같은 방식(최신 `candles_1m` 종가, SKIP LOCKED).

## Decision

**B + B2.**

```
paper_conditional_orders (V67)
  status: WAITING_PARENT → ACTIVE → EXECUTED | CANCELLED | FAILED
  trigger_type: TAKE_PROFIT·STOP_LOSS (SELL 전용) | PRICE_ABOVE·PRICE_BELOW (양방향)
  oco_group_id (2개 조건 = OCO), parent_order_id (체결 시 자동 등록), executed_order_id

PaperConditionalOrderTrigger (@Scheduled 3s)
  후보: ACTIVE ⋈ LATERAL 최신 종가 WHERE 조건 충족
  └─ PaperConditionalOrderFirer.fire(id)  — 트랜잭션 1개
       SELECT … FOR UPDATE SKIP LOCKED → 조건 재확인
       → OrderSubmitter.submitMarket(…, idempotencyKey = "PCO:{id}")   (@RiskChecked, 보유 수량 확인은 사가)
       → EXECUTED · 같은 OCO 그룹의 나머지 CANCELLED
     실패(리스크 한도·보유 부족) → 롤백 후 markFailed (REQUIRES_NEW) — 재시도하지 않는다

POST /api/paper/orders {…, takeProfitPrice?, stopLossPrice?}   ← 주문 패널 "체결 시 자동 등록"
  └─ 같은 트랜잭션에서 SELL TAKE_PROFIT + STOP_LOSS (OCO, 수량 = 매수 수량)
       부모 즉시 체결 → ACTIVE / 미체결 지정가 → WAITING_PARENT
  PaperConditionalParentListener (동기 @EventListener)
       OrderFilledEvent(orderId = 부모)    → WAITING_PARENT → ACTIVE  (체결과 같은 트랜잭션)
       OrderCancelledEvent(orderId = 부모) → WAITING_PARENT → CANCELLED
```

1. **발동은 시장가 주문 하나뿐**이다. 지정가 발동은 두지 않는다 — 모의투자에서 손절은 "빠져나오는 것"이 목적이다.
2. **리스크 게이트는 발동 시점에 다시 걸린다**(새 주문이므로). 막히면 FAILED + 사유. OCO 상대는 살려 둔다.
3. **등록 시 검증**: TP/SL은 SELL 전용, OCO는 서로 다른 두 조건이고 익절 > 손절, 자동 등록은 매수 전용이며 기준가(지정가
   또는 최근 종가)보다 익절은 위·손절은 아래. SELL 조건은 등록 시점 보유가 수량 이상이어야 한다.
4. **보유 수량을 예약하지 않는다.** 조건부 매도는 미체결 주문이 아니라 "조건"이다. 그 사이 사용자가 팔면 발동 시
   사가의 보유 확인이 거부하고 FAILED로 남는다. 실브로커 조건부 주문(ADR-032)과 같은 의미론이다.
5. 사용자당 살아 있는(ACTIVE·WAITING_PARENT) 조건부 주문은 50건. 상한 확인과 INSERT는 사용자 단위
   `pg_advisory_xact_lock`으로 직렬화한다.
6. 계좌 초기화(`reset`)는 살아 있는 조건부 주문을 모두 취소한다.

## Reasons

- **테이블 분리(B)**: 실브로커로 가는 코드 경로가 구조적으로 없다. paper 모듈은 brokerage에 의존하지 않고, 발동은
  `matching::submit`(모의 매칭 엔진)뿐이다 — Modulith 의존성이 이걸 강제한다.
- **스위퍼(B2)**: ADR-074와 같은 동시성 모형(행 락 + 상태 재확인 + 멱등 키)을 재사용한다. 모의투자에 틱 단위 반응성은
  필요 없다.
- **동기 부모 리스너**: 비동기면 "매수는 체결됐는데 손절이 아직 대기"인 창이 생긴다. 활성화는 체결의 일부다.
- `matching::submit`을 서버 안에서 쓰는 것은 [ADR-051](051-event-triggered-paper-orders.md)(watch rule)에 이어 두 번째다.
  두 경우 모두 사용자가 미리 선언한 모의 주문이고 멱등 키로 보호된다. [ADR-036](036-ai-order-proposal.md)의 "AI는 직접
  주문하지 않는다"는 그대로다 — ai 모듈은 여전히 `matching::submit`을 의존성에 나열할 수 없다.

## Consequences

- 발동 지연은 최대 스위퍼 주기(3초) + 분봉 갱신 지연. 1분 안의 일시적 돌파는 놓칠 수 있다(ADR-074와 같은 한계).
- 일시적 오류(DB 끊김 등)로 실패해도 FAILED로 남고 재시도하지 않는다. 사용자가 다시 등록해야 한다. 같은 거부를
  3초마다 반복하는 것보다 낫다고 판단했다.
- 실전 화면의 조건부 주문(`/brokerage/conditional-orders`)과 모의 조건부 주문은 화면에서도 계좌 표시(모의/실전)로 구분한다.

## Note (2026-10 보안 리뷰 후속)

- 발동 판정은 최신 1분봉이 5분(`CandleFreshness.MAX_AGE`) 이내일 때만 한다. 시세가 끊긴 동안의 마지막 봉으로 손절·익절이
  발동하지 않도록 그 주기를 건너뛴다(ADR-074 Note와 같은 기준).
- 발동 주문의 리스크 게이트(@RiskChecked)는 이제 미체결 모의 지정가 매수도 노출로 센다(ADR-074 Note).

## Revisit When

- 모의투자에도 틱 단위 반응성이 필요해질 때(예: 장중 대회) — B1로 옮긴다.
- 조건부 지정가·트레일링 스탑이 필요할 때.
- 실패의 재시도 정책(일시적 오류 vs 업무 거부 구분)이 필요해질 때.
