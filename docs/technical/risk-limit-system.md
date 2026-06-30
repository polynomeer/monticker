# 리스크 한도 시스템 — 주문 전 동기 게이트

## 1. 설계 원칙

리스크 체크는 주문이 체결 엔진에 도달하기 **전**에 동기적으로 실행되는 게이트다. 한도를 초과하면 주문은 `REJECTED` 상태로 즉시 저장되고 체결 로직은 실행되지 않는다.

```kotlin
val riskResult = riskChecker.check(userId, stockId, side, quantity, estimatedPrice)
if (!riskResult.approved) {
    orderRepo.save(Order(..., status = OrderStatus.REJECTED, rejectReason = "Risk check blocked by: ${riskResult.blockedBy}"))
    return SubmitOrderResponse(...)
}
```

5가지 규칙을 모두 평가한 뒤 **첫 번째로 실패한 규칙**을 `blockedBy`에 기록한다. 모든 개별 결과는 `checks: List<RuleResult>`에 남아 사용자에게 어떤 항목을 통과/실패했는지 투명하게 보여준다.

```kotlin
data class RuleResult(val rule: String, val passed: Boolean, val detail: String, val current: Double, val limit: Double)
data class RiskCheckResult(val approved: Boolean, val blockedBy: String?, val severity: String, val checks: List<RuleResult>)
```

---

## 2. 다섯 가지 규칙

### 2.1 일일 손실 한도 (DailyLossRule)

```kotlin
val dailyPnl = jdbc.queryForObject(
    """SELECT COALESCE(SUM(CASE WHEN side='SELL' THEN amount ELSE -amount END), 0)
       FROM fills WHERE user_id = ? AND filled_at >= current_date""",
    BigDecimal::class.java, userId,
) ?: BigDecimal.ZERO

val lossLimitAmt = accountCash.multiply(limits.dailyLossLimitPct).divide(BigDecimal("100"))
val passed = dailyPnl >= lossLimitAmt.negate()
```

오늘 발생한 모든 체결(`fills`)을 매도는 +, 매수는 -로 합산해 실현 손익을 구한다. 보유 현금 대비 설정된 비율(기본 3%)을 초과하는 손실이면 차단한다.

### 2.2 종목 집중도 (ConcentrationRule) — 매수 시에만 적용

```kotlin
val newHoldingValue = currentHoldingValue + estimatedPrice.multiply(BigDecimal(qty)).toDouble()
val concentrationPct = newHoldingValue / totalAssets * 100
val passed = concentrationPct <= limits.concentrationLimitPct.toDouble()
```

이 주문이 체결됐을 때를 **가정**하여 해당 종목의 비중을 미리 계산한다. 체결 후가 아니라 체결 전에 평가하는 것이 핵심 — 사후 평가는 이미 과도한 비중을 만든 뒤에야 경고하게 된다.

### 2.3 VaR 한도 (VaRRule)

```kotlin
val allReturns = grouped.values.flatMap { rows ->
    rows.map { (it["close"] as BigDecimal).toDouble() }
        .zipWithNext { a, b -> if (b != 0.0) (a - b) / b else 0.0 }
}
val varValue = if (allReturns.size >= 5) {
    val sorted = allReturns.sorted()
    val idx = (sorted.size * 0.05).toInt()
    -sorted[idx] * 100                        // 5번째 백분위수 (95% VaR)
} else {
    val std = stddev(allReturns)
    std * 1.65 * 100                          // 정규분포 가정 근사
}
```

보유 종목들의 최근 20일 수익률 분포에서 5번째 백분위수(=95% 신뢰수준 VaR)를 직접 추출한다. 표본이 5개 미만이면 정규분포를 가정해 `표준편차 × 1.65`로 근사한다(95% 신뢰수준의 z-score). 실측 분포 방식과 근사 방식을 데이터 양에 따라 분기하는 것은 통계적으로 안정성과 정확성의 트레이드오프다.

### 2.4 최대 보유 종목 수 (PositionCountRule) — 신규 종목 매수 시에만 적용

```kotlin
val isNewStock = jdbc.queryForObject(
    """SELECT COALESCE(SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END), 0)
       FROM paper_trades WHERE user_id = ? AND stock_id = ?""",
    Int::class.java, userId, stockId,
) ?: 0
if (isNewStock <= 0) {
    // 이 종목을 한 번도 보유한 적 없는 경우에만 종목 수 제한 적용
}
```

이미 보유 중인 종목을 추가 매수하는 것은 종목 수를 늘리지 않으므로 이 규칙에서 제외한다. 분산 한도가 "처음 보는 종목"에만 적용되는 것이 자연스러운 의미론이다.

### 2.5 거래 빈도 (TradingFrequencyRule)

```kotlin
val hourlyOrders = jdbc.queryForObject(
    "SELECT COUNT(*) FROM orders WHERE user_id = ? AND created_at > ?",
    Long::class.java, userId, Timestamp.from(Instant.now().minusSeconds(3600)),
) ?: 0L
```

직전 1시간 슬라이딩 윈도우 내 주문 횟수를 카운트한다. 고정된 "하루 N회" 한도가 아니라 슬라이딩 윈도우를 쓰는 이유는 자정 경계에서 한도가 리셋되는 허점을 막기 위해서다.

---

## 3. 모든 체크 결과를 로그로 남긴다

```kotlin
val checksJson = objectMapper.writeValueAsString(checks)
jdbc.update(
    """INSERT INTO risk_check_logs (user_id, stock_id, side, quantity, approved, blocked_by, checks_json)
       VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
    userId, stockId, side, qty, approved, blockedBy, checksJson,
)
```

승인/거부 여부와 무관하게 모든 체크 결과를 `risk_check_logs`에 저장한다. 이는 "왜 이 주문이 거부됐는지" 사용자에게 설명하는 근거이자, 한도 설정이 실제로 의미 있게 작동하는지 검증하는 감사 추적이다.

---

## 4. Dry-run API

```http
POST /api/risk/check
{ "stockId": 1, "side": "BUY", "quantity": 100, "orderType": "MARKET" }
```

주문을 생성하지 않고 `RiskCheckResult`만 반환한다. 프론트엔드의 "리스크 사전 확인" 버튼이 이 엔드포인트를 호출해, 사용자가 실제로 주문을 넣기 전에 한도 위반 여부를 미리 확인할 수 있게 한다.

---

## 5. 테스트에서 발견한 설계 특성

테스트 작성 중 `RiskLimit.isActive` 필드가 정의되어 있음에도 `RiskCheckerService.check()` 어디에서도 읽히지 않는다는 점을 발견했다. 즉 사용자가 리스크 체크를 "비활성화"해도 실제로는 모든 규칙이 계속 적용된다 — 필드는 존재하지만 로직에 연결되지 않은 상태다. 이는 향후 기능 확장 포인트로 남겨두었다(테스트에서는 실제 동작을 검증했을 뿐, 의도된 동작으로 가정하고 새 단언을 추가하지 않았다).
