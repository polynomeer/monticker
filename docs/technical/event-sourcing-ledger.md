# Investment Wallet — 이벤트 소싱 원장 패턴

## 1. 왜 원장 패턴인가

대부분의 모의투자 구현은 `paper_accounts.cash` 컬럼을 직접 증감시킨다. 이 방식은 "지금 잔고가 얼마인지"는 답하지만 "왜 이렇게 됐는지"는 답하지 못한다. monticker의 Investment Wallet은 모든 잔고 변화를 `ledger_events` 테이블에 **이벤트로 기록**하고, 이를 바탕으로 "내 돈 이동 타임라인"·"투자 영수증"·"주문 리플레이" 기능을 파생시킨다.

```sql
CREATE TABLE ledger_events (
    event_type      VARCHAR(30) NOT NULL,   -- DEPOSIT | FILL | SETTLEMENT | FEE | ...
    amount          NUMERIC(18,4) NOT NULL, -- 양수=증가, 음수=감소
    balance_after   NUMERIC(18,4),          -- 스냅샷 (재구성 가속용)
    paper_trade_id  BIGINT,
    stock_id        BIGINT,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL
);
```

`balance_after`를 매 이벤트마다 함께 저장하는 것은 순수 이벤트 소싱(매번 전체 재생)과 절충한 설계다. 임의 시점의 잔고를 구하려고 전체 이벤트를 순회할 필요 없이, 가장 가까운 이벤트의 `balance_after`를 읽으면 된다.

---

## 2. 매수/매도와 원장 기록의 연결

```kotlin
// PaperTradingService.buy()
val trade = tradeRepo.save(PaperTrade(...))
ledgerService.recordBuy(userId, trade.id, stockId, amount, account.cash)
```

```kotlin
// LedgerService
fun recordBuy(userId: Long, tradeId: Long, stockId: Long, amount: BigDecimal, balanceAfter: BigDecimal) {
    ledgerRepo.save(LedgerEvent(
        eventType = LedgerEventType.FILL,
        amount = amount.negate(),       // 매수는 항상 음수(현금 유출)
        balanceAfter = balanceAfter,
        paperTradeId = tradeId, stockId = stockId,
        description = "매수 체결",
    ))
}
```

매수는 `FILL`(현금 -), 매도는 `SETTLEMENT`(현금 +)로 기록한다. 이 둘을 같은 `eventType`으로 합치지 않고 분리한 이유는, 추후 "부분 체결"·"정산 대기" 같은 중간 상태를 추가할 때 두 흐름이 서로 다른 상태 전이를 가질 가능성이 크기 때문이다.

---

## 3. 파생 기능 — 원장에서 모든 것을 재구성

### 3.1 돈의 이동 지도 (WalletService)

```kotlin
fun getWalletMap(userId: Long): WalletMapResponse {
    val holdingsValue = calcHoldingsValue(userId)     // 보유 종목 평가액 (현재가 기준)
    val totalAssets = account.cash + holdingsValue
    return WalletMapResponse(
        availableCash = account.cash,
        reservedCash = BigDecimal.ZERO,                // 모의투자 환경에서는 항상 0
        holdingsValue = holdingsValue,
        settlementPending = BigDecimal.ZERO,            // 즉시 체결이므로 항상 0
        totalAssets = totalAssets,
        recentLedger = ledgerService.getLedger(userId).take(10),
    )
}
```

`reservedCash`·`settlementPending`이 항상 0인 이유는 현재 체결이 즉시(주문=체결) 이루어지기 때문이다. 도메인 모델에는 두 필드가 존재하므로, 향후 `MatchingService`의 LIMIT 주문 대기 상태와 연결하면 실제로 0이 아닌 값을 가질 수 있는 구조다.

### 3.2 투자 영수증 (ReceiptService)

```kotlin
val fee = trade.amount.multiply(BigDecimal("0.00015")).setScale(0, RoundingMode.HALF_UP)
val settledAmount = if (trade.side == "BUY") trade.amount + fee else trade.amount - fee

val ledgerEntry = ledgerRepo.findAll().filter { it.paperTradeId == tradeId }.maxByOrNull { it.createdAt }
val balanceBefore = if (trade.side == "BUY") balanceAfter + trade.amount else balanceAfter - trade.amount
```

체결 후 잔고(`balanceAfter`)는 원장에서 직접 읽고, 체결 전 잔고(`balanceBefore`)는 역산한다 — 매수라면 "체결 후 잔고 + 매수 금액 = 체결 전 잔고"가 성립하기 때문이다. 별도로 "체결 전 잔고"를 저장하지 않고 파생시키는 것이 원장 패턴의 핵심 이점이다.

### 3.3 주문 리플레이 (ReplayService)

```kotlin
val type = when (event.eventType) {
    "FILL" -> "BUY"; "SETTLEMENT" -> "SELL"
    "DEPOSIT" -> "DEPOSIT"; "WITHDRAWAL" -> "WITHDRAWAL"
    else -> return@mapNotNull null   // CASH_RESERVED 등 중간 상태 이벤트는 리플레이에서 숨김
}
```

리플레이는 사용자에게 보여줄 이벤트 타입만 화이트리스트로 골라낸다. `LedgerEventType`에는 `CASH_RESERVED`, `PARTIAL_FILL` 같은 내부 상태 전이도 정의되어 있지만, 사용자 경험 관점에서는 "매수/매도/입금/출금"만 의미가 있으므로 노출하지 않는다.

```kotlin
val totalPnl = ledgerEvents.filter { it.eventType == "SETTLEMENT" }.sumOf { it.amount }
    .subtract(ledgerEvents.filter { it.eventType == "FILL" }.sumOf { it.amount.abs() })
```

일일 손익은 "오늘 매도로 들어온 돈 합계 - 오늘 매수로 나간 돈 합계"로 근사한다. 정확한 실현손익(FIFO 매칭)은 아니지만, 하루 단위 현금흐름 관점에서는 직관적이고 계산이 단순하다.

---

## 4. 트레이드오프

**FIFO 정확도 포기**: 손익 계산이 매수-매도 쌍을 정확히 매칭하지 않고 일별 현금흐름 차감으로 근사한다. 여러 번 매수한 종목을 부분 매도하면 실제 실현손익과 어긋날 수 있다. `TaxOptimizerService`의 실현이익 계산도 동일한 단순화를 쓰며, 코드 주석에 명시했다.

```kotlin
// TaxOptimizerService — 동일한 설계 트레이드오프
// Simplification: realized YTD gain approximated as
// SUM(sell amount) - SUM(matching buy cost at average buy price for that stock) for sells this year.
// This is not FIFO-accurate but is a reasonable approximation for a paper-trading educational tool.
```

**조회 비용**: `ReceiptService`가 `ledgerRepo.findAll()`로 전체 원장을 가져온 뒤 필터링한다. 사용자당 거래량이 많아지면 `findByPaperTradeId` 같은 인덱스 기반 조회로 교체해야 한다.

**스냅샷 부재**: `wallet_snapshots` 테이블이 데이터 모델 문서에는 계획되어 있으나 아직 구현되지 않았다. 현재는 매 요청마다 `getLedger()` 전체를 읽고 최근 10건만 자르는 방식이라, 거래 이력이 누적될수록 조회 비용이 증가한다.
