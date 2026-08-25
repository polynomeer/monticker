# ADR-013: Append-Only Ledger for Wallet

## Status
Accepted

## Context

사용자의 투자 자산 상태를 어떻게 저장할 것인가를 결정해야 했다. 두 가지 방식이 있다:

**A) 잔고 직접 저장 (Mutable State)**
- `wallet` 테이블에 `available_cash`, `reserved_cash` 컬럼을 두고 거래마다 UPDATE
- 조회 단순, 하지만 이력 추적 불가. 잘못된 UPDATE 시 원인 파악 불가.

**B) Append-Only Ledger (이벤트 소싱 유사)**
- 모든 자산 변동을 `ledger_events` 테이블에 INSERT로만 기록
- 잔고는 이벤트 합산 또는 `wallet` 스냅샷 테이블에 캐시

금융 시스템에서 거래 기록은 **감사(Audit)** 목적으로 절대 삭제·수정해서는 안 된다는 원칙이 있다. 오류 시에도 역방향 이벤트(보정 이벤트)로 수정한다.

## Decision

**Append-Only Ledger** 방식을 채택한다.

- `ledger_events` 테이블: 모든 자산 변동을 INSERT만으로 기록. UPDATE/DELETE 없음.
- `wallet` 테이블: 실시간 잔고 스냅샷 (성능용 캐시). `ledger_events`에서 재계산 가능.
- 오류 시 보정 이벤트(역방향 금액)를 새 행으로 INSERT.

```
LedgerEventType:
  DEPOSIT, WITHDRAWAL, FILL, PARTIAL_FILL,
  CASH_RESERVED, CASH_UNRESERVED, FEE, SETTLEMENT,
  PAPER_SETTLEMENT_COMPLETE, SUBSCRIPTION_PAYMENT,
  CREATOR_EARNING_CREDITED, CREATOR_PAYOUT_PAID,
  BROKERAGE_SETTLEMENT
```

## Reasons

- **감사 추적**: 모든 자산 변동의 원인과 시점을 영구 보관. 규제·분쟁 대응에 필수.
- **재계산 가능성**: `wallet` 스냅샷이 오염되어도 `ledger_events`에서 전액 재계산 가능.
- **디버깅**: "내 잔고가 왜 이렇게 됐지?"를 이벤트 타임라인으로 추적 가능.
- **이벤트 타입 확장성**: 새 결제 유형 추가 시 이벤트 타입만 추가하면 되고 스키마 변경 불필요.

## Consequences

- **INSERT 비용**: 매 거래마다 ledger_events INSERT가 추가된다. 고빈도 거래 시 테이블이 빠르게 커진다.
- **집계 비용**: 잔고 재계산은 전체 이벤트 합산이 필요하다 → `wallet` 스냅샷 테이블로 완화.
- **스냅샷-원장 불일치 위험**: 버그로 스냅샷이 원장과 달라질 수 있다. 주기적 일관성 검증 필요.
- **파티셔닝**: 장기 운영 시 `ledger_events`를 user_id 또는 날짜로 파티셔닝해야 한다.

## Revisit When

- `ledger_events` 레코드가 수억 건을 넘을 때 → 아카이빙 + 파티셔닝 전략 수립.
- 실시간 잔고 갱신 TPS가 병목이 될 때 → 스냅샷 업데이트를 비동기화하고 eventual consistency 허용.
