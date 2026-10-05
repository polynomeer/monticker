# ADR-083: 다음 결제일은 저장하지 않고 갱신 잡 규칙으로 계산한다

## Status
Accepted

[ADR-053](053-payment-idempotency-and-failure-classification.md)·[ADR-059](059-payment-gap-closure.md)(정기결제 갱신)를 보완한다.

## Context

`/subscription` 화면은 "다음 결제일" 자리에 만료일(`expiresAt`)을 보여 줬다. 실제 청구는 만료일이 아니라 **갱신 잡이 도는 시각**에
일어난다: `BatchJobScheduler`가 매일 01:00 KST에 `subscriptionRenewalJob`을 돌리고, 리더가 `expiresAt <= 실행 시각 + 1일`인 ACTIVE
구독을 청구한다. 그래서 만료 14:00인 구독은 만료 **당일 01:00**에 청구된다. 해지(CANCELLED)·무료·카드 미등록 구독은 청구되지 않는데
(카드가 없으면 잡이 결제 거절로 기록하고 3회면 FREE로 내린다), 화면은 이를 구분하지 않았다.

고려한 대안:

- **A. `user_subscriptions.next_billing_at` 컬럼** — 연장·해지·카드 등록/해지·플랜 변경·PG 보류(ADR-053, 다음 실행으로 밀림) 때마다
  갱신해야 한다. 한 경로라도 빠뜨리면 화면이 틀린 날을 약속하고, 리더가 그 컬럼이 아니라 `expiresAt`을 보는 한 컬럼은 진실이 아니다.
  리더를 컬럼 기준으로 바꾸면 결제 경로를 건드리게 된다(이중청구 방어가 `expiresAt` 기반 orderId에 묶여 있다).
- **B. 응답 시 계산** — 채택.

## Decision

- `RenewalSchedule`(subscription.application)에 크론(`0 0 1 * * *`, Asia/Seoul)과 리더 선행 기간(1일)을 **한 곳에** 둔다. `BatchJobScheduler`의
  `@Scheduled`와 `SubscriptionRenewalJobConfig`의 리더 기준이 이 상수를 쓴다.
- `nextChargeAt(expiresAt, now)` = `max(expiresAt − 1일, now)` 이후 첫 크론 실행 시각.
- `SubscriptionService.billingSchedule()`: 유료·ACTIVE·만료일 있음·빌링키 있음일 때만 `nextBillingAt`과 금액(현재 플랜 가격)을 준다.
  아니면 `noChargeReason` = `FREE_PLAN | CANCELLED | NOT_ACTIVE | NO_BILLING_KEY`.
- `GET /api/subscription/me`에 `nextBillingAt`·`nextBillingAmount`·`noChargeReason`을 더한다(추가 필드). 마이그레이션 없음.

## Reasons

- 화면과 청구가 같은 규칙에서 나온다. 잡 시각을 바꾸면 화면도 같이 바뀐다.
- 쓰기 경로를 늘리지 않는다 — 결제·갱신 코드(ADR-053/059의 멱등성)를 건드리지 않는다.

## Consequences

- 표시값은 "예정"이다: PG 장애로 보류되면 다음 실행으로 밀리고(그때 다시 계산하면 반영된다), 카드가 거절되면 청구되지 않는다.
- 금액은 현재 플랜 가격이다. 가격이 바뀌면 다음 갱신은 바뀐 가격으로 청구된다(갱신 경로가 `plan.price`를 쓴다).
- 잡이 여러 레플리카에서 겹쳐 도는 문제(design-review P1 #8, 분산 락)는 이 결정과 무관하다.

## Revisit When

- 구독마다 결제일을 고정(예: 매월 가입일)하거나 연간 요금제(P2)를 도입할 때 — 주기가 구독별로 달라지면 저장 컬럼(A)을 다시 검토한다.
- 갱신 잡의 실행 주기·선행 기간을 바꿀 때 — `RenewalSchedule`만 바꾸면 된다.
