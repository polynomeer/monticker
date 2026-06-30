package com.monticker.api.backtest.domain.strategies

import com.monticker.api.backtest.domain.DailyCandle
import com.monticker.api.backtest.domain.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class EmaBreakoutStrategyTest {

    private fun candle(day: Int, close: Double, volume: Long) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close), high = BigDecimal(close), low = BigDecimal(close),
        close = BigDecimal(close), volume = volume,
    )

    @Test
    fun `returns HOLD when history is shorter than emaPeriod plus 2`() {
        val strategy = EmaBreakoutStrategy(emaPeriod = 20, multiplier = 1.5)
        val history = (0..5).map { candle(it, 100.0, 1000L) }

        val signal = strategy.onCandle(history.last(), history, mutableMapOf())

        assertThat(signal).isEqualTo(Signal.HOLD)
    }

    @Test
    fun `fires BUY when volume surges above the multiplier and price trend is up while not already holding`() {
        val strategy = EmaBreakoutStrategy(emaPeriod = 5, multiplier = 1.5)
        // Rising prices, then a volume spike + price increase on the last candle
        val history = (0..7).map { i ->
            val volume = if (i == 7) 10_000L else 1000L
            val close = 100.0 + i * 2
            candle(i, close, volume)
        }
        val state = mutableMapOf<String, Any>("holding" to false)

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.BUY)
    }

    @Test
    fun `does not fire BUY when volume is ordinary even if price trend is up`() {
        val strategy = EmaBreakoutStrategy(emaPeriod = 5, multiplier = 1.5)
        val history = (0..7).map { i -> candle(i, 100.0 + i * 2, 1000L) }
        val state = mutableMapOf<String, Any>("holding" to false)

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.HOLD)
    }

    @Test
    fun `fires SELL when holding and the trend reverses`() {
        val strategy = EmaBreakoutStrategy(emaPeriod = 5, multiplier = 1.5)
        // Declining prices while currently holding a position -> trend turns false -> SELL
        val history = (0..7).map { i -> candle(i, 200.0 - i * 5, 1000L) }
        val state = mutableMapOf<String, Any>("holding" to true)

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.SELL)
    }

    @Test
    fun `holds when already holding and trend remains intact with normal volume`() {
        val strategy = EmaBreakoutStrategy(emaPeriod = 5, multiplier = 1.5)
        val history = (0..7).map { i -> candle(i, 100.0 + i * 2, 1000L) }
        val state = mutableMapOf<String, Any>("holding" to true)

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.HOLD)
    }
}
