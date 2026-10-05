# ADR-080: 유료 전략 구독 — 결제는 서버에서 닫아 두고, 기존 결제 흐름 재사용 조건을 정한다

## Status
Accepted

## Context

전략 마켓 카드의 "유료 구독"은 화면에서만 막혀 있었다([ADR-035](035-strategy-market-signal-access-control.md) §4).
rollout plan P1은 "PG 결제 연동"을 요구하므로, 플랫폼 구독 결제([ADR-016](016-subscription-creator-revenue-sharing.md)·[053](053-payment-idempotency-and-failure-classification.md)·[059](059-payment-gap-closure.md))를 전략 구독에 그대로 쓸 수 있는지 확인했다.

**지금 서버 경로(`POST /api/quant/market/{id}/subscribe` → `CreatorEarningsService.onStrategySubscribed`)의 문제**
- 화면이 막아도 API는 직접 부를 수 있다.
- 결제 기록(`payment_records`)도, 서버가 쥐는 주문 ID도 없이 `pgClient.requestPayment()`를 바로 부른다. ADR-059가 플랫폼 구독에서 닫은 갭(서버 orderId·금액, PENDING 정리, 결과 불명 처리)이 여기서는 모두 열려 있다.
- 운영 PG(`TossPgClient.requestPayment`)는 "웹훅 confirm 흐름을 쓰라"는 스텁이라 항상 실패한다. 반대로 **Mock PG 환경에서는 실제 돈 없이 성공**하고, 곧바로 출금 가능한 제작자 수익(`creator_earnings` AVAILABLE)과 원장 기록이 생긴다. 출금 승인은 실제 계좌 이체로 이어진다.
- 결제 결과가 INDETERMINATE(응답 못 받음)여도 예외로 롤백한다. 청구됐을 수 있는데 구독도 기록도 남지 않는다.
- `strategy_subscriptions`에는 만료일·상태가 없다. 월 구독 갱신·해지·환불을 표현할 수 없다.
- 공유 시 가격 검증이 없어 음수·소수 가격도 저장됐다.

**재사용 가능한 것**: 서버 생성 orderId로 사전 주문을 만들고(`preparePayment`) 토스 SDK 결제 뒤 웹훅·confirm으로 확정하는 흐름, 빌링키 정기결제(`renewSubscription`), 결제 대조 배치(`PaymentReconciliationJobConfig`), 실패 분류(ADR-053).

**재사용에 필요한 변경**: `payment_records`가 플랜 FK(`plan`)에 묶여 있어 상품 종류(플랫폼 플랜/전략) 일반화가 필요하다. 전략 구독에 기간·상태·갱신 배치가 필요하고, 수익 적립을 결제 확정(웹훅) 시점으로 옮기며 `creator_earnings.payment_id`를 채워야 한다. 해지·환불 시 적립 취소 규칙도 필요하다.

**코드로 정할 수 없는 결정**
- 유사투자자문업 신고 대상 여부([legal-review-brief §2-2](../legal-review-brief.md)) — 유료 신호 판매가 바로 그 질문이다.
- 통신판매업 신고, 디지털 콘텐츠 청약철회·환불 정책.
- 제작자 정산의 원천징수·지급 기한(rollout 0-4, 법무 대기).
- 수수료율(현재 코드는 70/30)과 가격 상·하한의 사업 결정.

## Decision

- **유료 전략 구독은 서버에서 닫는다.** `subscribe()`는 가격이 0보다 크면 구독 행을 만들기 전에 409(`BusinessRuleException`)로 거절한다.
- **두 번째 문(fail-closed).** `CreatorEarningsService.onStrategySubscribed()`도 가격이 0이 아니면 PG를 부르기 전에 거절한다. 어느 호출자가 실수로 열어도 돈과 수익 적립이 움직이지 않는다.
- **공유 가격 검증.** 0원 이상 1,000,000원 이하의 정수 원만 받는다. 상한은 사업 결정 전 임시 안전값이다.
- 무료 전략 구독·신호 이력·접근 제어(ADR-035)는 그대로다.
- 화면은 유료 전략에 "결제가 아직 열리지 않았다"를 그대로 보여 준다. 제작자가 유료 가격으로 공유할 때도 같은 안내를 띄운다.
- 결제를 열 때는 위 "재사용에 필요한 변경"을 하나의 설계로 다시 정한다. 이 ADR은 그 전제 조건 목록을 겸한다. 결제 연동 자체는 하지 않는다.

## Reasons

- 출금 승인이 실제 이체로 이어지는 구조에서 "결제 없이 적립되는 수익"은 곧 회사 돈의 유출이다. 화면 비활성화는 방어가 아니다.
- 법무 판단 전에 유료 신호 판매를 여는 것은 코드 품질과 무관한 위험이다. 결제 흐름만 먼저 만들면 꺼진 채로 유지보수 부담만 생긴다.
- 기존 플랫폼 결제 흐름은 검증된 부품이 많다. 다만 플랜 FK·기간 모델 같은 구조 차이가 있어 "그대로 연결"이 아니라 일반화 설계가 필요하다. 그 설계는 법무 결정 뒤 요구사항(환불·청약철회)을 알고 하는 편이 맞다.

## Consequences

- 유료 가격의 전략은 마켓에 보이지만 아무도 구독할 수 없다. 제작자가 유료로 올리면 구독자가 0명으로 남는다.
- `onStrategySubscribed`의 PG 호출 코드는 지금 도달하지 않는다. 결제를 열 때 이 경로를 confirm 기반으로 다시 쓴다(그대로 되살리지 않는다).
- 1,000,000원 가격 상한은 근거 있는 값이 아니다. 사업 결정 때 바꾼다.

## Revisit When

- 유사투자자문업·통신판매업·정산 원천징수에 대한 법무 의견이 나왔을 때.
- 위 결정이 "판매 가능"이면: `payment_records` 상품 일반화 → 전략 구독 기간·상태 모델 → 사전 주문·confirm 기반 구독 → 갱신 배치·대조 배치 편입 → 결제 확정 시 수익 적립 순서로 설계 ADR을 새로 쓰고 이 ADR을 Superseded로 바꾼다.
