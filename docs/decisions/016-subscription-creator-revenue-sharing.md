# ADR-016: Subscription Plan and Creator Revenue Sharing Model

## Status
Accepted

## Context

monticker는 두 가지 수익 모델이 교차한다:

1. **구독 수익 (B2C SaaS)**: 사용자가 플랜(FREE/PRO/QUANT)을 구독해 월 정기 결제
2. **전략 마켓 (Creator Economy)**: QUANT 플랜 사용자가 자신의 퀀트 전략을 공개하고, 다른 사용자가 구독 → 제작자에게 수익 배분

이 두 흐름을 어떻게 데이터 모델과 정산 시스템으로 통합할 것인가를 결정해야 했다.

핵심 설계 결정:
- 구독 결제는 외부 PG(Toss)를 통해 처리 → 내부 Wallet에도 반영할 것인가?
- 전략 구독료의 배분율, 정산 주기, 최소 지급액을 어떻게 설계할 것인가?
- 제작자 수익의 출금 요청을 어떻게 처리할 것인가?

## Decision

**4개의 독립적 정산 도메인**을 설계하고, 각각 `LedgerEvent`로 연결한다.

### 구독 정산 (Subscription Settlement)

```
사용자 → Toss PG → payment_records (PENDING)
                 → /payment/confirm 호출
                 → payment_records (SUCCESS)
                 → subscriptions (ACTIVE)
                 → LedgerEvent: SUBSCRIPTION_PAYMENT (−금액)
```

PG 결제 성공이 확인된 후에만 구독 활성화. Webhook으로 결제 상태 동기화.

### 전략 구독 수익 배분 (Creator Earning)

```
전략 구독자의 구독료
    │
    ▼ 플랫폼 수수료 30% 차감
    ▼ 제작자 몫 70%
    │
    ▼ strategy_earnings 테이블에 적립
    ▼ LedgerEvent: CREATOR_EARNING_CREDITED (+금액)
```

배분율(70:30)은 초기값. `StrategyMarket` 정책 설정으로 관리.

### 제작자 출금 (Creator Payout)

- 최소 출금액: 10,000원 이상
- 출금 요청 → `payout_requests` 테이블 (PENDING)
- 관리자 승인 → 실제 계좌 이체 → `COMPLETED`
- LedgerEvent: `CREATOR_PAYOUT_PAID` (−금액)

## Reasons

### 4개 도메인 분리 이유

각 정산 유형은 트리거, 처리 주체, 실패 보상 방식이 다르다:

| 도메인 | 트리거 | 실패 보상 |
|--------|--------|---------|
| PaperSettlement | T+2 스케줄러 | 재실행 |
| SubscriptionSettlement | PG Webhook | PG Refund API |
| CreatorSettlement | 구독 완료 이벤트 | 보상 이벤트 INSERT |
| BrokerageSettlement | KIS API | 증권사 확인 필요 |

하나의 Settlement 서비스로 통합하면 복잡도와 장애 전파 범위가 커진다.

### LedgerEvent로 모든 자산 변동 통합

다양한 정산 도메인의 결과가 모두 `LedgerEvent`로 귀결되므로, 사용자는 Wallet 원장 타임라인 하나에서 모든 입출금 이력을 확인할 수 있다.

### 수익 적립 후 별도 출금 요청 방식

수익을 즉시 계좌 이체하는 방식 대신 플랫폼 내 크레딧으로 먼저 적립하는 이유:
- 소액 누적 후 일괄 이체로 이체 수수료 절감
- 최소 출금액 기준으로 소액 정산 비용 관리
- 세금 신고 편의 (제작자가 수익 명세를 플랫폼에서 확인 가능)

## Consequences

- **PG Webhook 의존성**: Toss Webhook이 유실되면 구독이 활성화되지 않을 수 있다. Webhook 재전송 정책 및 polling 보완 필요.
- **배분율 변경 이력**: `strategy_earning_rate`를 변경할 때 기존 구독자와의 계약 관계가 불분명해질 수 있다. 버전별 정책 이력 관리 필요.
- **출금 수동 승인**: 초기에는 관리자 수동 승인. 거래량 증가 시 자동화(Toss 지급대행) 필요.
- **세금 이슈**: 제작자 수익이 일정 금액 초과 시 사업소득/기타소득 신고 의무가 발생할 수 있다. 법적 검토 필요.
- **테스트**: 각 정산 도메인의 실패 시나리오(PG 오류, Webhook 중복, 보상 실패)를 독립적으로 테스트할 수 있다.

## Revisit When

- 전략 마켓 거래량이 증가해 수동 승인 병목이 생길 때 → Toss 지급대행 API 연동 자동화.
- 국세청 현금영수증 의무 발행 기준에 해당할 때 → CMS 연동.
- 배분율을 전략별로 다르게 설정해야 할 때 → `strategy_market_config` 테이블에 per-strategy rate 추가.
