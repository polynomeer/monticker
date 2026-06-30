package com.monticker.api.backtest.domain.strategies

import com.monticker.api.backtest.domain.DailyCandle
import com.monticker.api.backtest.domain.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class RsiStrategyTest {

    private fun candle(day: Int, close: Double) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close), high = BigDecimal(close), low = BigDecimal(close),
        close = BigDecimal(close), volume = 1000L,
    )

    @Test
    fun `defaults RSI to 50 when there is not enough history, producing HOLD`() {
        val strategy = RsiStrategy(period = 14, oversold = 30.0, overbought = 70.0)
        val history = (0..3).map { candle(it, 100.0) }
        val state = mutableMapOf<String, Any>()

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.HOLD)
        assertThat(state["rsi"]).isEqualTo(50.0)
    }

    @Test
    fun `fires BUY when RSI crosses back above the oversold threshold`() {
        val strategy = RsiStrategy(period = 14, oversold = 30.0, overbought = 70.0)
        val state = mutableMapOf<String, Any>("prevRsi" to 25.0)
        // Strongly recovering prices push current RSI above 30
        val history = (0..20).map { candle(it, 50.0 + it * 3) }

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.BUY)
    }

    @Test
    fun `fires SELL when RSI crosses back below the overbought threshold`() {
        val strategy = RsiStrategy(period = 14, oversold = 30.0, overbought = 70.0)
        val state = mutableMapOf<String, Any>("prevRsi" to 75.0)
        // Strongly declining prices push current RSI below 70
        val history = (0..20).map { candle(it, 200.0 - it * 3) }

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.SELL)
    }

    @Test
    fun `holds when RSI stays within the neutral zone`() {
        val strategy = RsiStrategy(period = 14, oversold = 30.0, overbought = 70.0)
        val state = mutableMapOf<String, Any>("prevRsi" to 50.0)
        val closes = listOf(100.0, 101.0, 100.0, 102.0, 101.0, 100.0, 101.0, 102.0, 101.0, 100.0,
            101.0, 102.0, 101.0, 100.0, 101.0)
        val history = closes.mapIndexed { i, c -> candle(i, c) }

        val signal = strategy.onCandle(history.last(), history, state)

        assertThat(signal).isEqualTo(Signal.HOLD)
    }

    @Test
    fun `updates prevRsi in state after every call so the next call can detect a cross`() {
        val strategy = RsiStrategy(period = 14, oversold = 30.0, overbought = 70.0)
        val state = mutableMapOf<String, Any>()
        val history = (0..20).map { candle(it, 100.0 + it) }

        strategy.onCandle(history.last(), history, state)

        assertThat(state).containsKey("prevRsi")
        assertThat(state).containsKey("rsi")
    }
}
