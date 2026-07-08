package com.monticker.api.backtest.domain.strategies

import com.monticker.api.backtest.domain.*

class EmaBreakoutStrategy(
    private val emaPeriod: Int = 20,
    private val multiplier: Double = 1.5,
) : BacktestStrategy {

    override fun name() = "EMA Breakout ($emaPeriod×$multiplier)"

    private fun calcEma(prices: List<Double>, period: Int): Double {
        val k = 2.0 / (period + 1)
        var ema = prices.first()
        prices.drop(1).forEach { ema = it * k + ema * (1 - k) }
        return ema
    }

    override fun onCandle(candle: DailyCandle, history: List<DailyCandle>, state: MutableMap<String, Any>): Signal {
        if (history.size < emaPeriod + 2) return Signal.HOLD

        val volumes = history.takeLast(emaPeriod + 1).map { it.volume.toDouble() }
        val emaVol   = calcEma(volumes.dropLast(1), emaPeriod)
        val currVol  = volumes.last()
        val surgeRatio = if (emaVol > 0) currVol / emaVol else 1.0

        val closes = history.takeLast(emaPeriod + 1).map { it.close.toDouble() }
        val emaPrev = calcEma(closes.dropLast(1), emaPeriod)
        val emaCurr = calcEma(closes, emaPeriod)
        val trend = emaCurr > emaPrev

        val holding = state["holding"] as? Boolean ?: false

        return when {
            !holding && surgeRatio >= multiplier && trend -> Signal.BUY
            holding  && (!trend || surgeRatio < 1.0)      -> Signal.SELL
            else -> Signal.HOLD
        }
    }
}
