package com.monticker.api.backtest.domain.strategies

import com.monticker.api.backtest.domain.*

class RsiStrategy(
    private val period: Int = 14,
    private val oversold: Double = 30.0,
    private val overbought: Double = 70.0,
) : BacktestStrategy {

    override fun name() = "RSI ($period, $oversold/$overbought)"

    private fun calcRsi(prices: List<Double>): Double {
        if (prices.size < period + 1) return 50.0
        val changes = prices.zipWithNext { a, b -> b - a }
        val gains = changes.takeLast(period).map { if (it > 0) it else 0.0 }
        val losses = changes.takeLast(period).map { if (it < 0) -it else 0.0 }
        val avgGain = gains.average()
        val avgLoss = losses.average()
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    override fun onCandle(candle: DailyCandle, history: List<DailyCandle>, state: MutableMap<String, Any>): Signal {
        val prices = history.map { it.close.toDouble() }
        val rsi = calcRsi(prices)
        state["rsi"] = rsi

        val prevRsi = state["prevRsi"] as? Double ?: 50.0
        state["prevRsi"] = rsi

        return when {
            prevRsi <= oversold  && rsi > oversold  -> Signal.BUY   // 과매도 탈출
            prevRsi >= overbought && rsi < overbought -> Signal.SELL  // 과매수 탈출
            else -> Signal.HOLD
        }
    }
}
