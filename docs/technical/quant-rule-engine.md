# Quant Lab 룰 엔진 — 코딩 없는 조건식 평가

## 1. 개요

Quant Lab의 룰셋 빌더는 사용자가 UI로 조합한 조건을 JSON DSL로 직렬화하고, 이를 백테스트와 (장래의) 실시간 신호 생성에 동일하게 사용한다. 핵심은 **지표 계산(IndicatorEngine)**과 **조건 평가(RuleEvaluator)**를 분리해, 새 지표 추가가 평가 로직을 건드리지 않도록 한 것이다.

```
JSON 룰셋
  → RuleDefinition { entryRules, exitRules, positionSizing }
  → RuleEvaluator.evaluateEntry/evaluateExit (idx 시점 평가)
       → IndicatorEngine.ma/rsi/macd/bollingerBands/avgVolume (계산)
```

---

## 2. IndicatorEngine — 지표 계산

모든 함수는 `(candles, period, upToIndex)` 시그니처를 따른다. `upToIndex`는 현재 평가 시점이며, 그 이후 데이터는 절대 참조하지 않는다(look-ahead bias 방지).

### RSI — Wilder Smoothing

```kotlin
var avgGain = changes.subList(0, period).filter { it > 0 }.sum() / period
var avgLoss = changes.subList(0, period).filter { it < 0 }.map { -it }.sum() / period

for (i in period until changes.size) {
    val gain = if (change > 0) change else 0.0
    val loss = if (change < 0) -change else 0.0
    avgGain = (avgGain * (period - 1) + gain) / period   // Wilder smoothing
    avgLoss = (avgLoss * (period - 1) + loss) / period
}
```

단순 이동평균이 아니라 Wilder의 지수적 평활화를 사용한다 — 매번 `(이전값 × (n-1) + 신규값) / n`으로 갱신하며, 이는 RSI의 표준 정의다. `avgLoss == 0`이면 RS가 무한대가 되므로 RSI를 100으로 직접 처리한다.

### MACD — EMA 12/26/9의 합성

```kotlin
fun macd(candles, upToIndex): MacdValue? {
    if (upToIndex < 33) return null  // 26 + 9 - 1, 신호선 계산에 필요한 최소 데이터
    val macdLine = ema(candles, 12, upToIndex) - ema(candles, 26, upToIndex)

    // 신호선 = MACD 라인의 EMA(9) — 과거 MACD 라인 시계열을 재구성해야 함
    val macdSeries = (25..upToIndex).map { i -> ema(candles, 12, i) - ema(candles, 26, i) }
    val signal = ema(macdSeries, 9)  // 9개 시드 평균 후 점화식 적용
    return MacdValue(macdLine, signal, macdLine - signal)
}
```

신호선(signal line)은 MACD 라인 자체의 EMA이므로, 단일 시점의 MACD 값만으로는 계산할 수 없다 — 과거 MACD 라인 전체 시계열을 다시 계산해야 한다. `idx`마다 `ema()`를 반복 호출하는 것은 `O(n²)`이지만, 종목 1개·기간 수년 규모의 백테스트에서는 실용적으로 충분히 빠르다.

### Bollinger Bands

```kotlin
val mean = slice.average()
val variance = slice.map { (it - mean).pow(2) }.average()
val stdDev = sqrt(variance)
return BollingerBands(mean + 2 * stdDev, mean, mean - 2 * stdDev)
```

표준 2σ 밴드. 모집단 분산(n으로 나눔)을 사용하며 표본 분산(n-1)이 아니다 — 이동 윈도우 전체를 모집단으로 취급하는 일반적인 금융 지표 관행을 따른다.

---

## 3. RuleEvaluator — 조건 DSL 해석

### 조건 구조

```json
{
  "indicator": "RSI",
  "comparator": "BETWEEN",
  "params": { "period": 14 },
  "value": [30, 70]
}
```

`compare()` 함수가 6가지 비교 연산자(`GT/GTE/LT/LTE/EQ/BETWEEN`)를 처리하며, `value`가 없는 경우 `impliedValue`(예: `CLOSE_VS_MA`의 이동평균값)로 폴백한다.

```kotlin
private fun compare(comparator: String, actual: Double, condValue: Any?, impliedValue: Double?): Boolean =
    when (comparator.uppercase()) {
        "GT" -> actual > (toDouble(condValue) ?: impliedValue ?: return false)
        "BETWEEN" -> {
            val list = condValue as? List<*> ?: return false
            actual in (list[0] as Number).toDouble()..(list[1] as Number).toDouble()
        }
        ...
    }
```

### 진입 조건과 청산 조건은 지표 집합이 다르다

```kotlin
private fun evaluateEntryCondition(cond, candles, idx): Boolean = when (cond.indicator.uppercase()) {
    "CLOSE_VS_MA"     -> ...
    "VOLUME_RATIO"    -> ...
    "RSI"             -> ...
    "MACD_CROSS"      -> ...
    "PRICE_CHANGE"    -> ...
    "BOLLINGER_BAND"  -> ...
    else -> false   // PROFIT_RATE, LOSS_RATE는 여기서 처리되지 않는다
}

private fun evaluateExitCondition(cond, candles, idx, entryPrice, currentPrice): Boolean = when (cond.indicator.uppercase()) {
    "PROFIT_RATE" -> compare(cond.comparator, returnPct, cond.value, null)
    "LOSS_RATE"   -> compare(cond.comparator, returnPct, cond.value, null)
    else          -> evaluateEntryCondition(cond, candles, idx)  // 나머지는 진입 평가로 위임
}
```

`PROFIT_RATE`/`LOSS_RATE`는 **포지션의 진입가 대비 현재 손익률**을 필요로 하므로 청산 시점에서만 의미가 있다 — 진입 평가에는 보유 포지션 자체가 없기 때문이다. 반대로 `RSI`, `MACD_CROSS` 등은 청산 조건에도 그대로 쓸 수 있어 `else -> evaluateEntryCondition(...)`으로 위임한다.

**테스트 작성 중 발견한 함정**: `PROFIT_RATE`를 "항상 참인 진입 조건"으로 쓰려고 시도했으나 `evaluateEntryCondition`의 `when`에 해당 분기가 없어 항상 `false`로 평가됐다. 이는 버그가 아니라 의도된 설계지만, 룰셋 빌더 UI에서 사용자가 `PROFIT_RATE`를 매수 조건으로 선택할 수 없도록 막아야 한다는 점을 시사한다.

### AND/OR 조합

```kotlin
private fun combine(operator: String, results: List<Boolean>): Boolean =
    when (operator.uppercase()) {
        "AND" -> results.all { it }
        "OR"  -> results.any { it }
        else  -> results.all { it }   // 알 수 없는 연산자는 AND로 폴백 (보수적 기본값)
    }
```

알 수 없는 연산자가 들어오면 조건을 더 쉽게 통과시키는 `OR`이 아니라 더 엄격한 `AND`로 처리한다 — 잘못된 입력값이 의도치 않게 매매 빈도를 늘리는 방향으로 작동하지 않도록 하는 안전한 기본값(fail-safe default) 선택이다.

---

## 4. QuantBacktestEngine — 신뢰도 점수

```kotlin
private fun calcReliability(tradeCount: Int, daysRange: Int): Pair<String, Map<String, Any>> {
    val score = when {
        tradeCount >= 50 && daysRange >= 730 -> "A"   // 2년 이상, 50회 이상
        tradeCount >= 20 && daysRange >= 365 -> "B"   // 1년 이상, 20회 이상
        tradeCount >= 10                     -> "C"
        else                                  -> "D"
    }
    ...
}
```

단순 수익률이 아니라 **검증 신뢰도**를 함께 제시한다. 거래 횟수가 적으면 통계적으로 우연일 가능성이 크고, 짧은 기간만 검증하면 특정 시장 국면에만 맞춰진 과최적화일 수 있다. 두 조건(거래 횟수 AND 기간)을 모두 요구하는 것은 "운 좋게 우상향 구간에서 10번 거래해 전부 수익"을 A등급으로 오인하지 않게 한다.

### 수수료·슬리피지 적용

```kotlin
private const val COMMISSION_RATE = 0.00015   // 0.015%
private const val SLIPPAGE_RATE   = 0.001     // 0.1%

val buyPrice = price * (1 + SLIPPAGE_RATE)
val commission = qty * buyPrice * COMMISSION_RATE
val cost = qty * buyPrice + commission
```

매수가는 종가보다 비싸게(`+슬리피지`), 매도가는 싸게(`-슬리피지`) 적용해 항상 불리한 방향으로 체결되도록 시뮬레이션한다. 이는 백테스트가 실제보다 낙관적인 결과를 내는 일반적인 함정을 줄이기 위한 보수적 가정이다.

---

## 5. 한계

- MACD 계산이 `O(n²)`이라 분봉 단위 장기 백테스트에는 비효율적이다.
- 신뢰도 점수는 거래 횟수·기간만 고려하며, **시장 국면 다양성**(상승장/하락장/횡보장 모두 포함했는지)은 반영하지 않는다 — 이는 `Quant Analytics`의 Regime Detector와 결합해야 완전해진다.
- `RuleEvaluator`는 stateless하다 — `MACD_CROSS` 같은 조건은 매 인덱스마다 `IndicatorEngine.macd()`를 두 번(현재·이전) 재계산한다.
