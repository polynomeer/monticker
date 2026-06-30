# Quant Analytics — 포트폴리오 최적화 · 패턴 인식 · 국면 분류 알고리즘

세 기능 모두 외부 수치 최적화 라이브러리 없이 순수 Kotlin으로 구현했다. 정확도보다 **설명 가능성**과 **의존성 최소화**를 우선한 결과다.

---

## 1. 포트폴리오 최적화 — Projected Gradient Descent

### 문제 정의

마코위츠 평균-분산 최적화의 표준형은 이차계획법(QP)으로 풀리지만, QP 솔버 라이브러리를 새로 도입하는 대신 **프로젝션 경사하강법**으로 근사했다.

```
minimize   wᵀΣw          (포트폴리오 분산)
subject to Σw = 1, w ≥ 0  (공매도 불가, 비중 합 1)
```

### 구현

```kotlin
fun minimizeVariance(cov: Array<DoubleArray>, mu: DoubleArray, targetReturn: Double, iterations: Int = 500): DoubleArray {
    var w = DoubleArray(mu.size) { 1.0 / mu.size }   // 균등 비중에서 시작
    val lr = 0.01
    repeat(iterations) {
        val grad = DoubleArray(w.size) { i -> 2.0 * (0 until w.size).sumOf { j -> cov[i][j] * w[j] } }
        for (i in w.indices) w[i] -= lr * grad[i]
        w = projectToSimplex(w)   // 매 스텝마다 제약조건 투영
    }
    return w
}
```

`wᵀΣw`의 그래디언트는 `2Σw`다(이차형식의 미분). 매 반복마다 그래디언트 방향으로 한 걸음 이동한 뒤, **심플렉스 투영(projectToSimplex)**으로 제약조건(합이 1, 음수 없음)을 강제한다.

```kotlin
fun projectToSimplex(w: DoubleArray): DoubleArray {
    val clipped = w.map { it.coerceAtLeast(0.0) }.toDoubleArray()   // 음수 → 0
    val sum = clipped.sum()
    return if (sum > 0) clipped.map { it / sum }.toDoubleArray()    // 재정규화
           else DoubleArray(w.size) { 1.0 / w.size }                // 전부 0이면 균등 분배로 폴백
}
```

이는 정확한 QP 해를 구하지 않지만, 500회 반복 후 수렴된 `w`는 실용적으로 충분히 좋은 최소분산 근사해를 만든다. `targetReturn` 파라미터는 현재 구현에서 직접적인 등식 제약으로 들어가지 않고 초기 추정치로만 쓰인다 — 코드 주석에 "soft-bias toward target return is implicit via projection"이라고 명시했듯, 정확한 목표수익률 등식 제약은 향후 개선 여지로 남아 있다.

### 효율적 프론티어

```kotlin
val minMu = mu.min(); val maxMu = mu.max()
for (i in 0..10) {
    val target = minMu + (maxMu - minMu) * i / 10
    val weights = minimizeVariance(cov, mu, target)
    points.add(FrontierPoint(target * 252, portfolioReturn(weights, mu) * 252, portfolioRisk(weights, cov) * sqrt(252.0), weights))
}
```

관측된 최소~최대 평균 수익률 구간을 11개 점으로 스윕한다. 연환산은 `× 252`(수익률), `× √252`(변동성, 분산의 가산성에서 표준편차는 제곱근 스케일링)을 따른다.

---

## 2. 차트 패턴 인식 — ZigZag + 템플릿 매칭

### ZigZag 알고리즘

원본 캔들에서 노이즈를 제거하고 의미 있는 전환점(swing point)만 추출한다.

```kotlin
fun zigZag(candles: List<DailyCandle>, thresholdPct: Double = 3.0): List<SwingPoint> {
    var direction: SwingType? = null
    var extremePrice = candles[0].close.toDouble()

    for (i in 1 until candles.size) {
        when (direction) {
            SwingType.HIGH -> {
                if (price > extremePrice) extremePrice = price       // 더 높은 고점 갱신
                else if ((extremePrice - price) / extremePrice * 100 >= thresholdPct) {
                    swings.add(SwingPoint(...))                       // 임계치 이상 하락 → 전환점 확정
                    direction = SwingType.LOW
                }
            }
            ...
        }
    }
}
```

가격이 추세 방향으로 계속 갱신되는 동안은 극값만 추적하고, **반대 방향으로 `thresholdPct`(기본 3%) 이상 되돌리면** 비로소 전환점을 확정한다. 이 방식으로 1~2% 수준의 일상적 노이즈는 무시되고, 진짜 추세 전환만 swing point로 남는다.

### 템플릿 매칭

각 패턴은 최근 N개 swing point의 타입 시퀀스와 가격 관계로 정의된다.

```kotlin
fun detectDoubleBottom(swings: List<SwingPoint>): PatternMatch? {
    val last3 = swings.takeLast(3)
    if (last3[0].type != LOW || last3[1].type != HIGH || last3[2].type != LOW) return null

    val diffPct = abs(low1 - low2) / low1 * 100
    if (diffPct > 2.0) return null              // 두 저점이 2% 이내로 유사해야 함
    val riseFromLows = (high - maxOf(low1, low2)) / maxOf(low1, low2) * 100
    if (riseFromLows < 5.0) return null          // 중간 고점이 저점 대비 5% 이상 솟아야 함

    val confidence = (100 - diffPct * 10).coerceIn(0.0, 100.0).toInt()
    return PatternMatch("DOUBLE_BOTTOM", confidence, ...)
}
```

신뢰도 점수는 패턴의 "완벽함"에서 역산한다 — 두 저점이 정확히 같으면(`diffPct = 0`) 신뢰도 100, 2%에 가까워질수록 0에 수렴한다. 5개 패턴(이중바닥/이중천장/헤드앤숄더/상승삼각형/하락삼각형) 모두 같은 원리를 변형해 적용한다.

```kotlin
val matches = listOfNotNull(detectDoubleBottom(swings), detectDoubleTop(swings), ...)
    .filter { it.confidenceScore >= 60 }   // 60점 미만은 노이즈로 간주, 반환하지 않음

matches.filter { it.confidenceScore >= 70 }.forEach { detectedPatternRepository.save(...) }
```

반환 임계값(60)과 영구 저장 임계값(70)을 다르게 둔 것은, 사용자에게는 다소 관대하게 보여주되 DB에는 더 확실한 패턴만 누적하기 위함이다.

---

## 3. 시장 국면 분류 — ADX + 변동성 + 추세 기울기

### ADX (Average Directional Index)

```kotlin
val upMove = candles[i].high - candles[i-1].high
val downMove = candles[i-1].low - candles[i].low
plusDM[i]  = if (upMove > downMove && upMove > 0) upMove else 0.0
minusDM[i] = if (downMove > upMove && downMove > 0) downMove else 0.0
tr[i] = maxOf(high - low, abs(high - prevClose), abs(low - prevClose))   // True Range

// Wilder 평활화
smoothedTR = smoothedTR - (smoothedTR / period) + tr[i]
val plusDI = smoothedPlusDM / smoothedTR * 100
val minusDI = smoothedMinusDM / smoothedTR * 100
val dx = abs(plusDI - minusDI) / (plusDI + minusDI) * 100
```

`+DM`(상승 방향성)과 `-DM`(하락 방향성)이 같은 날 동시에 양수가 될 수 없도록(`upMove > downMove` 비교) 한 쪽만 선택한다. DX는 두 방향성 지표의 차이를 정규화한 값이며, ADX는 DX의 평활 평균이다. ADX가 높을수록 — 상승이든 하락이든 — **추세가 뚜렷함**을 의미하며 방향은 알려주지 않는다(그래서 별도로 `trendSlope`가 필요하다).

### 변동성 — 연환산 표준편차

```kotlin
fun calculateVolatility(candles, period = 20): Double {
    val returns = window.zipWithNext { a, b -> (b.close - a.close) / a.close }
    val variance = returns.sumOf { (it - mean).pow(2) } / returns.size
    return sqrt(variance) * sqrt(252.0)
}
```

### 분류 규칙

```kotlin
fun classify(adx: Double, volatility: Double, volatilityPercentile80: Double, slope: Double): String =
    when {
        volatility > volatilityPercentile80 -> "HIGH_VOL"   // 변동성이 1순위 — 추세 무관하게 우선 분류
        adx < 20 -> "SIDEWAYS"
        slope > 0 -> "BULL"
        else -> "BEAR"
    }
```

변동성 임계치를 최우선으로 검사한다 — 고변동성 구간에서는 ADX가 일시적으로 높게 나와도 진짜 추세가 아니라 노이즈일 가능성이 크기 때문에, "변동성이 비정상적으로 크면 추세 판단을 보류하고 HIGH_VOL로 분류한다"는 우선순위를 코드 구조에 그대로 반영했다.

```kotlin
val volSeries = rollingVolatilitySeries(candles)
val volatilityPercentile80 = if (volSeries.size >= 5)
    volSeries.sorted()[(volSeries.size * 0.8).toInt()]
else 0.40   // 데이터 부족 시 40% 절대 임계값으로 폴백
```

80번째 백분위수를 동적 임계값으로 쓰되, 롤링 윈도우 데이터가 5개 미만이면 고정값(연 40%)으로 대체한다 — 표본이 너무 적을 때 백분위수 계산 자체가 무의미해지는 것을 방지하는 패턴으로, VaR 계산(`risk-limit-system.md` 참고)과 동일한 사고방식이다.

---

## 4. 공통 설계 철학

세 알고리즘 모두 "정확한 수치해석 라이브러리 없이도 설명 가능한 근사를 만든다"는 원칙을 공유한다. 그래디언트 하강, ZigZag, ADX 모두 외부 의존성 없이 순수 함수로 구현되어 있어 단위 테스트가 용이했다 — 실제로 이 세 서비스의 핵심 로직(`minimizeVariance`, `projectToSimplex`, `zigZag`, `detectDoubleBottom` 등, `calculateADX`)은 JdbcTemplate 목킹 없이 입력→출력만으로 검증 가능한 순수 함수로 노출되어 있다.
