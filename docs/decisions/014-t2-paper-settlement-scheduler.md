# ADR-014: T+2 Business Day Settlement for Paper Trading

## Status
Accepted

## Context

페이퍼 트레이딩(모의투자)에서 체결이 발생했을 때 자산을 즉시 지갑에 반영할 것인지,  
실제 주식 시장과 동일하게 **T+2 영업일** 후에 정산할 것인지를 결정해야 했다.

두 가지 선택지:

**A) 즉시 정산 (Instant Settlement)**
- 매도 체결 즉시 현금이 `available_cash`에 반영
- 구현이 단순하지만 실제 시장과 다름. 사용자가 잘못된 경험을 습득할 수 있음.

**B) T+2 영업일 정산 (Deferred Settlement)**
- 매도 체결 → `settlement_pending` 상태 → T+2 영업일 후 `available_cash`로 이동
- 실제 시장과 동일한 경험 제공. 학습 목적에 부합.

monticker의 목적은 **실제 투자와 유사한 환경에서 학습**이므로 B를 채택해야 한다.

## Decision

**T+2 영업일 정산 스케줄러** 방식을 채택한다.

- 매도 체결 시 `paper_settlements` 테이블에 `status=PENDING`, `settlement_date=T+2영업일`로 INSERT
- 매일 오전 9시 `PaperSettlementScheduler`가 `settlement_date <= today AND status=PENDING` 건을 일괄 처리
- 처리 시 `LedgerService.recordPaperSettlementComplete()` 호출 → 지갑에 반영

```kotlin
fun calculateSettlementDate(tradeDate: LocalDate): LocalDate {
    var date = tradeDate
    var count = 0
    while (count < 2) {
        date = date.plusDays(1)
        if (isBusinessDay(date)) count++  // 공휴일 제외
    }
    return date
}
```

공휴일 데이터는 정적 목록(연도별 업데이트)으로 관리한다.

## Reasons

- **현실 학습**: 실제 시장의 T+2 제도를 체험하게 해 "매도 후 즉시 재투자 가능"이라는 오해를 방지.
- **도메인 정확성**: 정산 대기(`settlementPending`) 상태를 지갑 모델에 명시적으로 표현.
- **스케줄러 단순성**: 일 1회 배치로 충분. 건당 실시간 처리보다 구현·운영이 단순.
- **멱등성**: 스케줄러가 중복 실행되어도 `status=PENDING` 조건으로 중복 처리 방지.

## Consequences

- **정산 지연**: 매도 체결 후 최대 2영업일 + 당일 스케줄러 실행 전까지 `available_cash`에 미반영.
- **공휴일 데이터 관리**: 매년 공휴일 목록을 업데이트해야 한다.
- **스케줄러 장애 복구**: 스케줄러가 하루 실행되지 않으면 다음 날 일괄 처리로 자동 복구 (`settlement_date <= today`).
- **시간대(Timezone)**: 스케줄러는 KST 기준으로 실행해야 한다. 서버가 UTC일 경우 cron 표현식에 +9시간 반영 필요.

## Revisit When

- 한국 거래소가 T+1으로 정산 주기를 단축할 경우 (미국은 2024년부터 T+1 전환).
- 실시간 정산 UX를 원하는 사용자 요구가 강할 때 → 즉시 정산 옵션을 플랜별로 제공 검토.
