package com.monticker.api.backtest.domain.strategies

import com.monticker.api.backtest.domain.*

class MaCrossoverStrategy(
    private val shortPeriod: Int = 5,
    private val longPeriod: Int = 20,
) : BacktestStrategy {

    override fun name() = "MA Crossover ($shortPeriod/$longPeriod)"

    override fun onCandle(candle: DailyCandle, history: List<DailyCandle>, state: MutableMap<String, Any>): Signal {
        if (history.size < longPeriod) return Signal.HOLD
        val prices = history.takeLast(longPeriod).map { it.close.toDouble() }
        val shortMa = prices.takeLast(shortPeriod).average()
        val longMa  = prices.average()
        val prevShortMa = prices.dropLast(1).takeLast(shortPeriod).average()
        val prevLongMa  = prices.dropLast(1).average()

        val crossedAbove = prevShortMa <= prevLongMa && shortMa > longMa
        val crossedBelow = prevShortMa >= prevLongMa && shortMa < longMa

        return when {
            crossedAbove -> Signal.BUY
            crossedBelow -> Signal.SELL
            else         -> Signal.HOLD
        }
    }
}
