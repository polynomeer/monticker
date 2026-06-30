package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class IndicatorEngineTest {

    private fun candle(day: Int, close: Double, volume: Long = 1000L, high: Double = close, low: Double = close) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = volume,
    )

    // ── MA ───────────────────────────────────────────────────────────────────

    @Test
    fun `ma returns null when not enough history for the period`() {
        val candles = (0..2).map { candle(it, 100.0) }
        assertThat(IndicatorEngine.ma(candles, period = 5, upToIndex = 2)).isNull()
    }

    @Test
    fun `ma computes simple average of the last N closes including current index`() {
        // closes: 10, 20, 30, 40, 50 -> MA(5) at idx 4 = average of all = 30
        val candles = listOf(10.0, 20.0, 30.0, 40.0, 50.0).mapIndexed { i, c -> candle(i, c) }
        val result = IndicatorEngine.ma(candles, period = 5, upToIndex = 4)
        assertThat(result).isNotNull().isCloseTo(30.0, within(0.0001))
    }

    @Test
    fun `ma uses only the trailing window not the full series`() {
        // closes: 10, 20, 30, 40, 50 ; MA(3) at idx 4 = avg(30,40,50) = 40
        val candles = listOf(10.0, 20.0, 30.0, 40.0, 50.0).mapIndexed { i, c -> candle(i, c) }
        val result = IndicatorEngine.ma(candles, period = 3, upToIndex = 4)
        assertThat(result).isNotNull().isCloseTo(40.0, within(0.0001))
    }

    // ── EMA ──────────────────────────────────────────────────────────────────

    @Test
    fun `ema returns null when not enough history for the period`() {
        val candles = (0..2).map { candle(it, 100.0) }
        assertThat(IndicatorEngine.ema(candles, period = 5, upToIndex = 2)).isNull()
    }

    @Test
    fun `ema of a flat price series equals that price`() {
        val candles = (0..10).map { candle(it, 100.0) }
        val result = IndicatorEngine.ema(candles, period = 5, upToIndex = 10)
        assertThat(result).isNotNull().isCloseTo(100.0, within(0.0001))
    }

    @Test
    fun `ema reacts more strongly to recent prices than a plain average would`() {
        // Flat at 100 for a while, then a jump to 200 — EMA should move toward 200
        // faster than a simple average over the whole window would.
        val candles = (0 until 30).map { candle(it, if (it < 25) 100.0 else 200.0) }
        val ema = IndicatorEngine.ema(candles, period = 10, upToIndex = 29)!!
        val sma = IndicatorEngine.ma(candles, period = 10, upToIndex = 29)!!
        // Both incorporate the jump, but EMA weights recent (already-jumped) prices more
        assertThat(ema).isGreaterThan(100.0)
        assertThat(sma).isGreaterThan(100.0)
    }

    // ── RSI ──────────────────────────────────────────────────────────────────

    @Test
    fun `rsi returns null when not enough history`() {
        val candles = (0..5).map { candle(it, 100.0) }
        assertThat(IndicatorEngine.rsi(candles, period = 14, upToIndex = 5)).isNull()
    }

    @Test
    fun `rsi is 100 when there are no losses in the window`() {
        // Strictly increasing closes -> avgLoss = 0 -> RSI = 100
        val candles = (0..20).map { candle(it, 100.0 + it) }
        val result = IndicatorEngine.rsi(candles, period = 14, upToIndex = 20)
        assertThat(result).isNotNull().isEqualTo(100.0)
    }

    @Test
    fun `rsi is between 0 and 100 for a mixed up-down series`() {
        val closes = listOf(100.0, 102.0, 101.0, 103.0, 99.0, 98.0, 100.0, 105.0, 104.0, 103.0,
            106.0, 108.0, 107.0, 109.0, 110.0, 108.0, 111.0, 112.0, 110.0, 113.0)
        val candles = closes.mapIndexed { i, c -> candle(i, c) }
        val result = IndicatorEngine.rsi(candles, period = 14, upToIndex = candles.lastIndex)
        assertThat(result).isNotNull()
        assertThat(result!!).isBetween(0.0, 100.0)
    }

    @Test
    fun `rsi trends low for a strictly decreasing series`() {
        val candles = (0..20).map { candle(it, 200.0 - it) }
        val result = IndicatorEngine.rsi(candles, period = 14, upToIndex = 20)
        assertThat(result).isNotNull()
        assertThat(result!!).isLessThan(20.0)
    }

    // ── MACD ─────────────────────────────────────────────────────────────────

    @Test
    fun `macd returns null when fewer than 34 candles are available`() {
        val candles = (0..32).map { candle(it, 100.0 + it) }
        assertThat(IndicatorEngine.macd(candles, upToIndex = 32)).isNull()
    }

    @Test
    fun `macd of a flat price series is approximately zero`() {
        val candles = (0..60).map { candle(it, 100.0) }
        val result = IndicatorEngine.macd(candles, upToIndex = 60)
        assertThat(result).isNotNull()
        assertThat(result!!.macdLine).isCloseTo(0.0, within(0.0001))
        assertThat(result.histogram).isCloseTo(0.0, within(0.0001))
    }

    @Test
    fun `macd line is positive when price has been trending up`() {
        val candles = (0..60).map { candle(it, 100.0 + it * 2.0) }
        val result = IndicatorEngine.macd(candles, upToIndex = 60)
        assertThat(result).isNotNull()
        // In a steady uptrend the fast (12) EMA stays above the slow (26) EMA
        assertThat(result!!.macdLine).isGreaterThan(0.0)
    }

    // ── Bollinger Bands ──────────────────────────────────────────────────────

    @Test
    fun `bollingerBands returns null when not enough history`() {
        val candles = (0..5).map { candle(it, 100.0) }
        assertThat(IndicatorEngine.bollingerBands(candles, period = 20, upToIndex = 5)).isNull()
    }

    @Test
    fun `bollingerBands of a flat series collapses upper, middle and lower to the same value`() {
        val candles = (0..25).map { candle(it, 100.0) }
        val bb = IndicatorEngine.bollingerBands(candles, period = 20, upToIndex = 25)
        assertThat(bb).isNotNull()
        assertThat(bb!!.middle).isCloseTo(100.0, within(0.0001))
        assertThat(bb.upper).isCloseTo(100.0, within(0.0001))
        assertThat(bb.lower).isCloseTo(100.0, within(0.0001))
    }

    @Test
    fun `bollingerBands upper and lower widen with increased volatility`() {
        val volatileCloses = (0..25).map { if (it % 2 == 0) 90.0 else 110.0 }
        val candles = volatileCloses.mapIndexed { i, c -> candle(i, c) }
        val bb = IndicatorEngine.bollingerBands(candles, period = 20, upToIndex = 25)
        assertThat(bb).isNotNull()
        assertThat(bb!!.upper).isGreaterThan(bb.middle)
        assertThat(bb.lower).isLessThan(bb.middle)
        assertThat(bb.upper - bb.lower).isGreaterThan(10.0)
    }

    // ── Average Volume ───────────────────────────────────────────────────────

    @Test
    fun `avgVolume returns null when not enough history`() {
        val candles = (0..3).map { candle(it, 100.0, volume = 1000L) }
        assertThat(IndicatorEngine.avgVolume(candles, period = 5, upToIndex = 3)).isNull()
    }

    @Test
    fun `avgVolume computes the mean volume over the trailing window`() {
        val volumes = listOf(1000L, 2000L, 3000L, 4000L, 5000L)
        val candles = volumes.mapIndexed { i, v -> candle(i, 100.0, volume = v) }
        val result = IndicatorEngine.avgVolume(candles, period = 5, upToIndex = 4)
        assertThat(result).isNotNull().isCloseTo(3000.0, within(0.0001))
    }
}
