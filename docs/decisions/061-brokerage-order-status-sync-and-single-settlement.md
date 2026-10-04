# ADR-061: 접수된 실거래 주문의 상태를 주기적으로 동기화하고, 체결 하나에 정산은 하나만

## Status
Accepted

[ADR-056](056-brokerage-order-unknown-outcome.md)(결과 불명 대조)이 `UNKNOWN`·`PENDING_SUBMIT`을 다룬다면, 이 ADR은 그 다음 상태인
`SUBMITTED`(증권사가 접수했고 아직 체결을 모름)를 다룬다.

## Context

[2026-10 설계 리뷰](../design-review-2026-10.md)와 [ADR-058](058-risk-gate-in-flight-exposure.md) 리뷰에서:

1. **지정가가 체결돼도 우리 장부는 모른다.** `SUBMITTED` 주문이 `FILLED`가 되는 경로는 제출 직후 1회 조회(시장가용), 사용자의
   "다시 확인", ADR-058의 같은 종목 매수 직전 갱신뿐이다. 장중에 지정가가 체결되면 **T+2 정산 기록(`brokerage_settlements`)과 그에
   이어지는 원장 반영이 사용자가 화면에서 확인할 때까지 생기지 않는다.** 정산 배치(T+2 17:00)는 정산 기록이 있는 것만 처리한다.
2. **체결 반영 경로가 여럿인데 일부는 행을 잠그지 않는다.** 제출 결과 기록(tx2)·대조·수동 확정은 행 락을 잡지만, 사용자 동기화
   (`syncOrderStatus`)와 ADR-058 갱신(`refreshOpenBuys`)은 잡지 않는다. `brokerage_settlements.order_id`에는 유일 제약이 없다.
   → 두 경로가 같은 체결을 동시에 보면 **정산 기록이 두 개** 생긴다(같은 매도 대금이 두 번 입금된다).

고려한 대안:
- **증권사 체결 푸시**(Toss `personal:order` 채널) — 가장 정확하지만 KIS에는 같은 것이 없고, 계좌별 상시 연결이 필요하다. 후속.
- **주기 조회** — 단순하고 두 증권사 공통. 채택.

## Decision

### 1. 동기화 잡

`BrokerageOrderStatusSyncJob`이 **60초마다** `SUBMITTED` 주문(증권사 주문번호 있음, **최근 24시간** 접수 — 당일 유효 주문, ADR-058과 같은 창)을
`status_synced_at` 오래된 순(NULL 먼저)으로 **최대 50건** 가져와 `getOrderStatus`로 확인하고 체결·취소·거부를 반영한다.

- 행은 `FOR UPDATE SKIP LOCKED`로 가져간다(레플리카 간 중복 없음, 다른 경로가 잡고 있으면 이번 주기는 건너뛴다).
- 확인할 때마다 `status_synced_at`을 갱신해 50건 상한 아래서도 모든 주문이 돌아가며 확인된다.
- 조회 실패(증권사 장애·자격증명 문제)는 아무것도 바꾸지 않는다 — 다음 주기에 다시.
- 24시간 넘은 `SUBMITTED`는 보지 않는다. 증권사에서는 이미 소멸했을 텐데 KIS 상태 조회는 **당일** 목록만 보므로 확인할 수 없다 —
  후속(증권사 일별 조회로 마감 처리).

### 2. 모든 체결 반영 경로가 행 락을 잡는다

`syncOrderStatus`(사용자)와 `refreshOpenBuys`(ADR-058)도 `findWithLockById`로 주문을 잡고 연다. 이제 `SUBMITTED → FILLED`를
바꾸는 다섯 경로(tx2·대조·수동 확정·사용자 동기화·판정 직전 갱신)와 이 잡이 모두 같은 행 락 아래서 상태를 다시 읽는다 —
`applyBrokerStatus`의 "이미 FILLED면 정산을 만들지 않는다" 조건이 경합 없이 성립한다.

### 3. 마지막 방어선 — 체결 하나에 정산 하나

```sql
CREATE UNIQUE INDEX ux_brokerage_settlements_order_id ON brokerage_settlements (order_id) WHERE order_id IS NOT NULL;
```

락을 잊은 새 경로가 생겨도 DB가 두 번째 정산을 거부한다(그 트랜잭션은 롤백되고, 먼저 반영한 쪽이 남는다).

### 4. 관측

`brokerage_order_status_sync_total{result=filled|cancelled|rejected|unchanged|lookup_failed}`.

## Reasons

- 정산·원장이 사용자 행동에 의존하지 않는다. 장중 지정가 체결이 다음 주기(≤ 1분 + 대기열) 안에 장부에 들어온다.
- 동시성은 새 장치가 아니라 이미 쓰던 행 락을 빠진 경로에 채워 넣는 것으로 닫는다. DB 제약은 그 규칙이 다시 깨져도 돈이 두 번
  들어가지 않게 하는 안전망이다.

## Consequences

- **증권사 호출이 늘어난다.** 미체결 주문 수만큼, 1분에 최대 50회. KIS `getOrderStatus`는 호출마다 당일 주문 목록 전체를 받아 걸러
  내므로 같은 계좌의 미체결이 많으면 비효율이다 — 계좌별로 목록을 한 번만 받는 최적화는 후속. 사용자 앱키의 레이트리밋 안이다.
- 체결 반영 지연은 최대 1분(+ 미체결이 50건을 넘으면 그만큼 순번). 실시간이 필요하면 Toss 체결 푸시.
- 부분 체결(`PARTIALLY_FILLED`)은 여전히 반영하지 않는다 — 전량 체결 시 한 번에 반영된다(ADR-058은 잔량을 대기 노출로 센다).
- 장 마감 후·주말에도 잡이 돈다(대상이 24시간 창이라 대부분 비어 있다).

## Revisit When

- Toss `personal:order` 같은 체결 푸시를 붙일 때 — 주기 조회는 누락 보정용으로 간격을 늘린다.
- 계좌당 미체결이 많아져 증권사 호출이 부담될 때 — 계좌별 당일 목록 1회 조회로 묶는다.
