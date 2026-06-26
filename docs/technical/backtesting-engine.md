# 백테스팅 엔진 설계 — 과거 데이터 기반 전략 검증

## 1. 개요

투자 전략을 실제 자금으로 시험하기 전에 과거 데이터로 검증하는 과정을 백테스팅이라 한다. monticker의 백테스팅 엔진은 일봉 데이터를 기반으로 세 가지 내장 전략을 시뮬레이션하고, 수익률·리스크 지표를 반환한다.

백테스팅 구현에서 가장 중요한 원칙은 **look-ahead bias 방지**다. 특정 시점 `t`의 신호를 계산할 때 `t` 이후 데이터를 사용하면 실제 운용 시 재현 불가능한 결과가 나온다. 엔진은 이를 다음과 같이 강제한다.

```kotlin
val history = filtered.subList(0, idx + 1)   // 현재 봉 포함, 미래 봉 제외
val signal  = strategy.onCandle(candle, history, strategyState)
```

`history`는 항상 현재 봉까지만 포함되므로 전략 구현체는 구조적으로 미래를 참조할 수 없다.

---

## 2. 전략 인터페이스

모든 전략은 `BacktestStrategy` 인터페이스를 구현한다.

```kotlin
interface BacktestStrategy {
    fun name(): String
    fun onCandle(
        candle:  DailyCandle,
        history: List<DailyCandle>,   // 현재 봉 포함 과거 전체
        state:   MutableMap<String, Any>  // 전략별 상태 저장소
    ): Signal
}
```

`Signal`은 `BUY`, `SELL`, `HOLD` 세 값을 가진다. 반환값이 `BUY`이면 엔진이 매수를 실행하고, `SELL`이면 청산을 시도한다. `HOLD`는 아무 행동도 하지 않는다.

`state`는 호출 간 공유되는 변경 가능 맵이다. 이전 봉의 RSI 값처럼 상태를 유지해야 하는 경우 여기에 저장한다. 엔진은 이 맵을 전략 생명주기 동안 보존하며, 전략 구현체가 직접 관리한다.

---

## 3. 세 가지 전략 상세

### 3.1 MA Crossover

단기 이동평균(MA)이 장기 이동평균을 아래에서 위로 교차할 때 매수, 위에서 아래로 교차할 때 매도한다. 기본값은 단기 5일, 장기 20일이다.

```kotlin
val prices    = history.takeLast(longPeriod).map { it.close.toDouble() }
val shortMa   = prices.takeLast(shortPeriod).average()
val longMa    = prices.average()
val prevShortMa = prices.dropLast(1).takeLast(shortPeriod).average()
val prevLongMa  = prices.dropLast(1).average()

val crossedAbove = prevShortMa <= prevLongMa && shortMa > longMa  // 골든크로스
val crossedBelow = prevShortMa >= prevLongMa && shortMa < longMa  // 데드크로스
```

핵심은 크로스오버가 *발생한 시점*에만 신호를 낸다는 점이다. 단기 MA가 이미 장기 MA 위에 있어도 교차가 없으면 HOLD를 반환한다. 이로써 추세가 시작되는 순간에만 진입한다.

`longPeriod`개의 봉이 쌓이기 전까지는 HOLD를 반환하여 불충분한 데이터로 잘못된 신호를 내는 것을 방지한다.

### 3.2 RSI (Relative Strength Index)

RSI는 최근 N일 동안의 상승폭 평균과 하락폭 평균의 비율로 과매수/과매도 상태를 측정한다. 기본값은 14일 기간, 과매도 30, 과매수 70이다.

```
avgGain = 최근 period일 중 상승분의 평균
avgLoss = 최근 period일 중 하락분의 평균 (절댓값)
RS  = avgGain / avgLoss
RSI = 100 - (100 / (1 + RS))
```

구현에서는 단순 이동 평균 방식을 사용한다.

```kotlin
val gains = changes.takeLast(period).map { if (it > 0) it else 0.0 }
val losses = changes.takeLast(period).map { if (it < 0) -it else 0.0 }
val avgGain = gains.average()
val avgLoss = losses.average()
```

신호 발생 조건은 **구간 탈출**이다. RSI가 과매도 구간(≤30)에 있다가 위로 벗어날 때 매수, 과매수 구간(≥70)에 있다가 아래로 벗어날 때 매도한다. 단순히 RSI가 30 미만인 상태를 매수 신호로 삼으면 하락 추세 중에 연속 매수가 발생하므로, 탈출 시점을 신호로 쓰는 것이 더 안전하다.

```kotlin
val prevRsi = state["prevRsi"] as? Double ?: 50.0
state["prevRsi"] = rsi

return when {
    prevRsi <= oversold   && rsi > oversold   -> Signal.BUY
    prevRsi >= overbought && rsi < overbought -> Signal.SELL
    else -> Signal.HOLD
}
```

### 3.3 EMA Breakout

거래량 급증과 EMA 상승 추세를 복합적으로 사용한다. 기본값은 EMA 기간 20일, 거래량 배수 1.5배다.

매수 조건은 두 가지를 동시에 만족해야 한다.
1. 현재 거래량이 거래량 EMA의 `multiplier`배 이상 (기본 1.5배) — 비정상적 관심 신호
2. 가격 EMA가 전봉 EMA보다 상승 중 — 추세 확인

```kotlin
val surgeRatio = if (emaVol > 0) currVol / emaVol else 1.0
val trend = emaCurr > emaPrev

return when {
    !holding && surgeRatio >= multiplier && trend -> Signal.BUY
    holding  && (!trend || surgeRatio < 1.0)      -> Signal.SELL
    else -> Signal.HOLD
}
```

EMA 계산은 지수 가중 이동평균 방식을 따른다.

```
k   = 2 / (period + 1)
EMA = 현재값 × k + 이전 EMA × (1 - k)
```

거래량과 가격 EMA를 모두 직전 봉 데이터로 계산한 뒤 현재 봉과 비교하므로 look-ahead bias가 없다.

---

## 4. 시뮬레이션 루프

`BacktestEngine.run()`은 날짜순으로 정렬된 봉 데이터를 순회하며 다음 흐름을 반복한다.

```
for each candle:
  1. history = candles[0..idx]  (현재 봉 포함)
  2. signal  = strategy.onCandle(candle, history, state)
  3. 보유 중이면:
     a. 손절 조건 확인: changePct <= -stopLossPct
     b. 익절 조건 확인: changePct >= takeProfitPct
     c. 전략 SELL 신호 확인
     → 조건 충족 시 청산, exitReason 기록
  4. 미보유이고 BUY 신호이면:
     a. 가용 현금의 95%로 최대 수량 계산
     b. 매수 실행
  5. 당일 평가자산 = cash + holding × price 기록
  6. MDD 추적용 peakEquity 갱신
```

`cash * 0.95`로 매수 수량을 제한하는 이유는 현금을 완전히 소진하면 거래 비용(실제 환경)이나 예기치 않은 슬리피지로 잔고 부족이 발생할 수 있기 때문이다. 시뮬레이션에서는 세금·수수료가 없으므로 5%는 단순 안전 여유다.

시뮬레이션 기간 마지막 봉에서 포지션이 남아 있으면 강제 청산하고 `exitReason = "END"`로 기록한다. 이는 "만약 오늘 팔았다면" 기준의 성과를 반영한다.

---

## 5. 성과 지표 계산

### 5.1 Sharpe Ratio

```
Sharpe = (연간 기대수익률 - 무위험수익률) / 연간 변동성
       = (avgDailyReturn × 252 - 0.03) / (stdDailyReturn × √252)
```

일별 수익률을 equity curve의 연속된 두 값으로 계산한다.

```kotlin
val dailyReturns = equity.zipWithNext { a, b -> (b.equity - a.equity) / a.equity }
val avgReturn = dailyReturns.average()
val stdReturn = sqrt(dailyReturns.map { (it - avgReturn).pow(2) }.average())
val sharpe = if (stdReturn > 0) (avgReturn * 252 - 0.03) / (stdReturn * sqrt(252.0)) else 0.0
```

연환산 승수로 252를 사용하는 이유는 미국 주식시장 기준 연간 거래일 수다. 한국 시장은 약 248~250일이지만 국제 관행에 따라 252를 사용한다. 무위험수익률 3%는 국내 단기 국채 수익률을 근사한 값이다.

### 5.2 최대 낙폭 (MDD)

```kotlin
val maxDd = equity.maxOfOrNull { it.drawdown } ?: 0.0
```

`drawdown`은 각 봉에서 고점 대비 하락률로 이미 계산되어 있으므로, 전체 시뮬레이션 기간 중 최댓값이 MDD다.

고점 추적은 O(n)으로 구현된다.

```kotlin
peakEquity = maxOf(peakEquity, totalEquity)
val drawdown = (peakEquity - totalEquity) / peakEquity * 100
```

### 5.3 Profit Factor

```
Profit Factor = 총수익 합계 / 총손실 합계 (절댓값)
```

1.0 이상이면 수익이 손실보다 크다는 뜻이다. 손실 거래가 없으면 99.9를 반환하여 무한대 표시를 방지한다.

```kotlin
val totalProfit = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
val totalLoss   = trades.filter { it.pnl < 0 }.sumOf { -it.pnl }
val profitFactor = if (totalLoss > 0) totalProfit / totalLoss else if (totalProfit > 0) 99.9 else 0.0
```

---

## 6. 한계

**슬리피지 미반영**: 실제 체결은 요청 가격과 다르게 이루어진다. 특히 유동성이 낮은 종목은 슬리피지가 수익률에 큰 영향을 미친다. 현재 구현은 `close` 가격에 즉시 체결된다고 가정한다.

**거래비용 없음**: 증권사 수수료(0.015~0.05%)와 매도 시 거래세(0.18~0.2%)가 반영되지 않는다. 단기 매매 전략은 이 비용으로 실제 수익률이 크게 낮아질 수 있다.

**생존자 편향**: 백테스팅 대상 종목이 현재까지 상장 유지된 기업이다. 과거에 상장폐지된 종목은 포함되지 않으므로 전략 성과가 실제보다 낙관적으로 평가될 수 있다.

**단일 종목 시뮬레이션**: 현재 엔진은 종목 1개를 대상으로 실행한다. 포트폴리오 수준의 분산 효과나 리밸런싱 전략은 지원하지 않는다.

---

## 7. 데이터 소스

현재 백테스팅은 `candles_1d` 테이블(일봉)만 사용한다. 모든 전략은 일봉의 `close` 가격을 기준으로 신호를 계산한다.

향후 분봉 확장 시 `DailyCandle`을 일반 `Candle` 타입으로 변경하고, `BacktestStrategy` 인터페이스의 시그니처를 수정하면 동일 엔진을 재사용할 수 있다. 단, 분봉 데이터는 볼륨이 훨씬 크므로 메모리 내 전체 로드 방식 대신 스트리밍 처리가 필요하다.
