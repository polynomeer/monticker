# 포트폴리오 리스크 지표 — 금융 수학과 구현

## 1. 개요

수익률만으로 포트폴리오를 평가할 수 없다. 10% 수익을 올렸더라도 도중에 자산의 40%가 사라졌다가 회복한 것과, 안정적으로 10%를 달성한 것은 근본적으로 다르다. 같은 수익률이라도 변동성이 낮을수록, 시장 하락에 덜 연동될수록 더 우수한 포트폴리오다.

monticker의 `RiskCalculator`는 이 판단에 필요한 다섯 가지 지표를 순수 함수로 제공한다: Sharpe Ratio, Beta, MDD, VaR 95%, 연환산 변동성.

---

## 2. 지표별 수식과 구현

### 2.1 Sharpe Ratio

Sharpe Ratio는 리스크 한 단위당 초과 수익률을 측정한다. 값이 클수록 동일한 위험 대비 수익이 높다. 1.0 이상이면 양호, 2.0 이상이면 우수로 일반적으로 평가한다.

```
Sharpe = (E[R] × T - Rf) / (σ × √T)

E[R]: 일별 평균 수익률
T:    연간 거래일 수 (252)
Rf:   무위험수익률 (0.03, 연 3%)
σ:    일별 수익률 표준편차
```

```kotlin
fun sharpe(dailyReturns: List<Double>): Double {
    if (dailyReturns.size < 5) return 0.0
    val avg = dailyReturns.average()
    val std = stdDev(dailyReturns)
    if (std == 0.0) return 0.0
    return (avg * TRADING_DAYS - RISK_FREE_RATE) / (std * sqrt(TRADING_DAYS))
}
```

**무위험수익률 3% 선택 근거**: 한국 3년 국채 수익률의 장기 평균을 근사한 값이다. 실시간 연동이 없으므로 상수로 고정했다. 실제 국채 금리가 상승하면 이 값을 조정해야 한다.

**연환산 승수 √252**: 수익률은 `×252`로 연환산하고, 표준편차는 `×√252`로 연환산한다. 이는 일별 수익률이 독립적이라는 가정에서 나온다. 분산은 시간에 선형 비례(`×T`)하므로 표준편차는 `×√T`가 된다.

### 2.2 Beta

Beta는 포트폴리오가 벤치마크에 비해 얼마나 민감하게 움직이는지를 나타낸다.

```
Beta = Cov(Rp, Rb) / Var(Rb)

Rp: 포트폴리오 일별 수익률 시계열
Rb: 벤치마크 일별 수익률 시계열
```

```kotlin
fun beta(portfolioReturns: List<Double>, benchmarkReturns: List<Double>): Double {
    val n = minOf(portfolioReturns.size, benchmarkReturns.size)
    if (n < 5) return 1.0
    val p = portfolioReturns.takeLast(n)
    val b = benchmarkReturns.takeLast(n)
    val varB = variance(b)
    return if (varB == 0.0) 1.0 else covariance(p, b) / varB
}
```

Beta 1.0은 시장과 동일하게 움직임을 의미한다. 1.5이면 시장이 10% 오를 때 포트폴리오는 15% 오른다(반대도 마찬가지).

**동일가중 포트폴리오를 벤치마크로 사용하는 이유**: KOSPI 지수 일별 수익률 데이터를 실시간으로 수집하는 파이프라인이 현재 없다. 대안으로 보유 종목들의 동일가중 평균 수익률을 벤치마크로 사용한다. 이는 `PaperTradingService.getRiskMetrics()`에서 다음과 같이 처리된다.

```kotlin
// 동일가중 포트폴리오 일별 평균가격
val portfolioPrices = dates.map { d -> priceMap[d]?.values?.average() ?: 0.0 }
val portfolioReturns = portfolioPrices.zipWithNext { a, b -> if (a == 0.0) 0.0 else (b - a) / a }

// Beta 계산 시 포트폴리오 수익률 자신을 벤치마크로도 넘김
beta = RiskCalculator.beta(portfolioReturns, portfolioReturns)
```

이 경우 Beta는 항상 1.0에 가깝게 나온다. 실제 시장 민감도를 측정하지 못하는 한계가 있으며, KOSPI 데이터 연동 후 개선이 필요하다.

### 2.3 최대 낙폭 (MDD, Maximum Drawdown)

MDD는 자산이 고점에서 저점까지 얼마나 하락했는지를 백분율로 나타낸다. 투자자가 가장 불운한 시점에 진입했을 때 얼마나 잃을 수 있는지를 보여주는 지표다.

```
MDD = max over all t of (Peak(t) - V(t)) / Peak(t) × 100
Peak(t) = max(V(0), V(1), ..., V(t))
```

```kotlin
fun maxDrawdown(equityCurve: List<Double>): Double {
    if (equityCurve.isEmpty()) return 0.0
    var peak = equityCurve[0]
    var mdd  = 0.0
    equityCurve.forEach { v ->
        peak = maxOf(peak, v)
        val dd = (peak - v) / peak * 100
        mdd = maxOf(mdd, dd)
    }
    return mdd
}
```

**O(n) 구현**: 배열을 한 번만 순회하면서 고점(`peak`)과 현재까지의 최대 낙폭(`mdd`)을 동시에 갱신한다. 정렬이나 이중 루프가 필요 없다.

`drawdownSeries()`는 같은 알고리즘으로 각 날짜의 낙폭 시계열을 반환한다. 프론트엔드에서 낙폭 차트를 그릴 때 사용된다.

### 2.4 VaR 95% (Value at Risk)

VaR 95%는 "95% 확률로 하루 손실이 이 값을 넘지 않는다"는 의미다. 역사적 시뮬레이션 방법을 사용한다.

```kotlin
fun var95(dailyReturns: List<Double>): Double {
    if (dailyReturns.size < 10) return 0.0
    val sorted = dailyReturns.sorted()
    val idx = (dailyReturns.size * 0.05).toInt().coerceAtLeast(0)
    return -sorted[idx] * 100
}
```

수익률을 오름차순 정렬하면 가장 왼쪽이 최악의 손실이다. 하위 5% 분위수를 취하면 그것이 VaR 95%다. 반환값을 양수로 만들기 위해 부호를 반전시킨다.

예를 들어 반환값이 2.5라면 "95% 확률로 하루 손실은 2.5% 이내"라고 해석한다.

### 2.5 연환산 변동성

```kotlin
fun annualizedVolatility(dailyReturns: List<Double>): Double {
    if (dailyReturns.size < 5) return 0.0
    return stdDev(dailyReturns) * sqrt(TRADING_DAYS) * 100
}
```

일별 수익률의 표준편차에 `√252`를 곱하여 연간 변동성으로 환산한다. 이는 수익률이 독립 동일 분포(i.i.d.)라는 가정 하에 분산의 시간 가산성에서 유도된다. 결과는 백분율(%)로 반환된다.

---

## 3. 데이터 파이프라인

리스크 지표 계산의 입력 데이터는 다음 경로로 준비된다.

```
candles_1d (DB)
  → 보유 종목의 일별 close 가격 조회
  → 연속된 날짜 쌍으로 일별 수익률 계산
      r_t = (close_t - close_{t-1}) / close_{t-1}
  → 동일가중 포트폴리오 수익률로 합산
  → RiskCalculator.* 호출
```

SQL 쿼리는 `candles_1d`에서 보유 종목 전체의 일별 close를 가져온다.

```sql
SELECT stock_id,
       DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
       close
FROM candles_1d
WHERE stock_id IN (...)
ORDER BY d, stock_id
```

날짜별로 그룹화한 뒤 종목별 가격을 맵으로 변환하고, 각 날짜에 존재하는 종목들의 평균값을 포트폴리오 가격 시계열로 삼는다.

---

## 4. 동일가중 벤치마크의 한계

현재 구현의 가장 큰 한계는 Beta 계산에 있다. 벤치마크로 포트폴리오 자신의 수익률을 사용하기 때문에 Beta는 항상 1.0에 가깝게 수렴한다.

```kotlin
beta = RiskCalculator.beta(portfolioReturns, portfolioReturns)
// 동일한 데이터를 넣으면 Cov(X,X) / Var(X) = Var(X) / Var(X) = 1.0
```

실제 의미 있는 Beta를 계산하려면 KOSPI 200 또는 KRX 300 지수의 일별 수익률 데이터가 필요하다. 현재는 이 데이터 수집 파이프라인이 없어서 자기 자신을 벤치마크로 사용하는 임시 구현이다.

---

## 5. RiskCalculator 순수 함수 설계

`RiskCalculator`는 `object`(싱글턴)로 선언되고 모든 메서드는 입력에만 의존하는 순수 함수다.

```kotlin
object RiskCalculator {
    fun sharpe(dailyReturns: List<Double>): Double { ... }
    fun beta(portfolioReturns: List<Double>, benchmarkReturns: List<Double>): Double { ... }
    fun maxDrawdown(equityCurve: List<Double>): Double { ... }
    fun var95(dailyReturns: List<Double>): Double { ... }
    fun annualizedVolatility(dailyReturns: List<Double>): Double { ... }
}
```

이 설계의 장점은 다음과 같다.
- **테스트 용이성**: Spring 컨텍스트 없이 단위 테스트 가능. `RiskCalculator.sharpe(listOf(0.01, -0.02, ...))`처럼 직접 호출한다.
- **재사용성**: 백테스팅 엔진과 모의투자 서비스 양쪽에서 동일 구현을 사용한다.
- **부작용 없음**: DB 접근, 외부 호출, 상태 변경이 전혀 없으므로 결과가 결정론적이다.

---

## 6. 향후 개선

**실 KOSPI 데이터 연동**: KRX API 또는 Yahoo Finance에서 KOSPI 200 일별 종가를 수집하여 `market_index_1d` 테이블에 저장하고, Beta 계산 시 이 데이터를 벤치마크로 사용한다.

**롤링 윈도우 계산**: 현재는 전체 기간의 단일 지표를 반환한다. 30일, 90일, 180일 롤링 Sharpe나 롤링 Beta를 계산하면 전략이 시간에 따라 어떻게 변하는지 추적할 수 있다.

**Sortino Ratio 추가**: Sharpe Ratio는 상방 변동성과 하방 변동성을 구분하지 않는다. 하방 변동성만 사용하는 Sortino Ratio를 추가하면 손실 리스크를 더 정확하게 측정할 수 있다.
