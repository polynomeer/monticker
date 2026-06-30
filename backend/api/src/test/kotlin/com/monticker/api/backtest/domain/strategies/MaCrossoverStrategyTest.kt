package com.monticker.api.backtest.domain.strategies

import com.monticker.api.backtest.domain.DailyCandle
import com.monticker.api.backtest.domain.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MaCrossoverStrategyTest {

    private fun candle(day: Int, close: Double) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close), high = BigDecimal(close), low = BigDecimal(close),
        close = BigDecimal(close), volume = 1000L,
    )

    @Test
    fun `returns HOLD when history is shorter than the long period`() {
        val strategy = MaCrossoverStrategy(shortPeriod = 5, longPeriod = 20)
        val history = (0..5).map { candle(it, 100.0) }

        val signal = strategy.onCandle(history.last(), history, mutableMapOf())

        assertThat(signal).isEqualTo(Signal.HOLD)
    }

    @Test
    fun `returns BUY when the short MA crosses above the long MA`() {
        val strategy = MaCrossoverStrategy(shortPeriod = 3, longPeriod = 6)
        // Flat at 100 then a sharp recent rise so the short MA pulls above the long MA
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 130.0)
        val history = closes.mapIndexed { i, c -> candle(i, c) }

        val signal = strategy.onCandle(history.last(), history, mutableMapOf())

        assertThat(signal).isEqualTo(Signal.BUY)
    }

    @Test
    fun `returns SELL when the short MA crosses below the long MA`() {
        val strategy = MaCrossoverStrategy(shortPeriod = 3, longPeriod = 6)
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 70.0)
        val history = closes.mapIndexed { i, c -> candle(i, c) }

        val signal = strategy.onCandle(history.last(), history, mutableMapOf())

        assertThat(signal).isEqualTo(Signal.SELL)
    }

    @Test
    fun `returns HOLD when short and long MA stay aligned with no cross`() {
        val strategy = MaCrossoverStrategy(shortPeriod = 3, longPeriod = 6)
        val history = (0..10).map { candle(it, 100.0) }

        val signal = strategy.onCandle(history.last(), history, mutableMapOf())

        assertThat(signal).isEqualTo(Signal.HOLD)
    }
}
