package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.RuleCondition
import com.monticker.api.quant.domain.RuleGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class RuleEvaluatorTest {

    private fun candle(day: Int, close: Double, volume: Long = 1000L) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close),
        high = BigDecimal(close),
        low = BigDecimal(close),
        close = BigDecimal(close),
        volume = volume,
    )

    // ── CLOSE_VS_MA ──────────────────────────────────────────────────────────

    @Test
    fun `CLOSE_VS_MA with GT is true when close is above the moving average`() {
        // closes flat at 100 for 19 days then jump to 200 on day 20 (idx 19)
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 200.0) }
        val cond = RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 20))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 19)

        assertThat(result).isTrue()
    }

    @Test
    fun `CLOSE_VS_MA with GT is false when close is below the moving average`() {
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 50.0) }
        val cond = RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 20))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 19)

        assertThat(result).isFalse()
    }

    @Test
    fun `CLOSE_VS_MA returns false when not enough history for the period`() {
        val candles = (0..3).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 20))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 3)

        assertThat(result).isFalse()
    }

    // ── VOLUME_RATIO ─────────────────────────────────────────────────────────

    @Test
    fun `VOLUME_RATIO with GT fires when today's volume exceeds the multiple of average`() {
        val candles = (0..19).map { candle(it, 100.0, volume = if (it < 19) 1000L else 5000L) }
        val cond = RuleCondition(indicator = "VOLUME_RATIO", comparator = "GT", params = mapOf("period" to 20), value = 2.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 19)

        assertThat(result).isTrue()
    }

    @Test
    fun `VOLUME_RATIO with GT does not fire for ordinary volume`() {
        val candles = (0..19).map { candle(it, 100.0, volume = 1000L) }
        val cond = RuleCondition(indicator = "VOLUME_RATIO", comparator = "GT", params = mapOf("period" to 20), value = 2.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 19)

        assertThat(result).isFalse()
    }

    // ── RSI ──────────────────────────────────────────────────────────────────

    @Test
    fun `RSI BETWEEN passes when RSI falls inside the configured range`() {
        // mixed series keeps RSI away from the extremes
        val closes = listOf(100.0, 102.0, 101.0, 103.0, 99.0, 98.0, 100.0, 105.0, 104.0, 103.0,
            106.0, 108.0, 107.0, 109.0, 110.0)
        val candles = closes.mapIndexed { i, c -> candle(i, c) }
        val cond = RuleCondition(indicator = "RSI", comparator = "BETWEEN", params = mapOf("period" to 14), value = listOf(0.0, 100.0))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, candles.lastIndex)

        assertThat(result).isTrue()
    }

    @Test
    fun `RSI BETWEEN fails when RSI falls outside the configured range`() {
        // strictly increasing -> RSI = 100, range [0, 50] excludes it
        val candles = (0..20).map { candle(it, 100.0 + it) }
        val cond = RuleCondition(indicator = "RSI", comparator = "BETWEEN", params = mapOf("period" to 14), value = listOf(0.0, 50.0))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 20)

        assertThat(result).isFalse()
    }

    // ── PRICE_CHANGE ─────────────────────────────────────────────────────────

    @Test
    fun `PRICE_CHANGE GT fires when N-day return exceeds the threshold`() {
        // idx-period close = 100, current close = 110 -> +10%
        val candles = (0..10).map { candle(it, if (it == 0) 100.0 else if (it == 10) 110.0 else 100.0) }
        val cond = RuleCondition(indicator = "PRICE_CHANGE", comparator = "GT", params = mapOf("period" to 10), value = 5.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 10)

        assertThat(result).isTrue()
    }

    @Test
    fun `PRICE_CHANGE returns false when index is before the lookback period`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "PRICE_CHANGE", comparator = "GT", params = mapOf("period" to 10), value = 5.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 5)

        assertThat(result).isFalse()
    }

    // ── MACD_CROSS ───────────────────────────────────────────────────────────

    @Test
    fun `MACD_CROSS GOLDEN requires at least 2 candles of MACD history`() {
        val candles = (0..32).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "MACD_CROSS", comparator = "GOLDEN")

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 0)

        assertThat(result).isFalse()
    }

    @Test
    fun `MACD_CROSS with unsupported comparator returns false`() {
        val candles = (0..60).map { candle(it, 100.0 + it) }
        val cond = RuleCondition(indicator = "MACD_CROSS", comparator = "SIDEWAYS")

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 60)

        assertThat(result).isFalse()
    }

    // ── BOLLINGER_BAND ───────────────────────────────────────────────────────

    @Test
    fun `BOLLINGER_BAND ABOVE_UPPER fires when close breaks above the upper band`() {
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 200.0) }
        val cond = RuleCondition(indicator = "BOLLINGER_BAND", comparator = "ABOVE_UPPER", params = mapOf("period" to 20))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 19)

        assertThat(result).isTrue()
    }

    @Test
    fun `BOLLINGER_BAND BELOW_LOWER fires when close breaks below the lower band`() {
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 1.0) }
        val cond = RuleCondition(indicator = "BOLLINGER_BAND", comparator = "BELOW_LOWER", params = mapOf("period" to 20))

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 19)

        assertThat(result).isTrue()
    }

    // ── Unknown indicator ────────────────────────────────────────────────────

    @Test
    fun `unknown indicator name evaluates to false`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "NOT_A_REAL_INDICATOR", comparator = "GT", value = 1.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 5)

        assertThat(result).isFalse()
    }

    // ── AND / OR combination ─────────────────────────────────────────────────

    @Test
    fun `AND group requires every condition to pass`() {
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 200.0, volume = if (it < 19) 1000L else 5000L) }
        val passingCond = RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 20))
        val failingCond = RuleCondition(indicator = "VOLUME_RATIO", comparator = "GT", params = mapOf("period" to 20), value = 100.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(passingCond, failingCond)), candles, 19)

        assertThat(result).isFalse()
    }

    @Test
    fun `OR group passes when at least one condition passes`() {
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 200.0, volume = if (it < 19) 1000L else 5000L) }
        val passingCond = RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 20))
        val failingCond = RuleCondition(indicator = "VOLUME_RATIO", comparator = "GT", params = mapOf("period" to 20), value = 100.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("OR", listOf(passingCond, failingCond)), candles, 19)

        assertThat(result).isTrue()
    }

    @Test
    fun `unrecognised operator falls back to AND semantics`() {
        val candles = (0..19).map { candle(it, if (it < 19) 100.0 else 200.0, volume = if (it < 19) 1000L else 5000L) }
        val passingCond = RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 20))
        val failingCond = RuleCondition(indicator = "VOLUME_RATIO", comparator = "GT", params = mapOf("period" to 20), value = 100.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("XOR", listOf(passingCond, failingCond)), candles, 19)

        assertThat(result).isFalse()
    }

    // ── Comparators ──────────────────────────────────────────────────────────

    @Test
    fun `BETWEEN comparator returns false when value is not a two-element list`() {
        val candles = (0..20).map { candle(it, 100.0 + it) }
        val cond = RuleCondition(indicator = "RSI", comparator = "BETWEEN", params = mapOf("period" to 14), value = 50.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 20)

        assertThat(result).isFalse()
    }

    @Test
    fun `EQ comparator matches an exact value`() {
        val candles = (0..20).map { candle(it, 100.0 + it) }
        // RSI of a strictly increasing series is exactly 100.0
        val cond = RuleCondition(indicator = "RSI", comparator = "EQ", params = mapOf("period" to 14), value = 100.0)

        val result = RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, 20)

        assertThat(result).isTrue()
    }

    // ── Exit conditions ──────────────────────────────────────────────────────

    @Test
    fun `PROFIT_RATE exit fires when current price gain meets the threshold`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = 8.0)

        val result = RuleEvaluator.evaluateExit(
            RuleGroup("OR", listOf(cond)), candles, 5, entryPrice = 100.0, currentPrice = 110.0,
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `PROFIT_RATE exit does not fire below the threshold`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = 8.0)

        val result = RuleEvaluator.evaluateExit(
            RuleGroup("OR", listOf(cond)), candles, 5, entryPrice = 100.0, currentPrice = 103.0,
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `LOSS_RATE exit fires when current price loss breaches the negative threshold`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "LOSS_RATE", comparator = "LTE", value = -4.0)

        val result = RuleEvaluator.evaluateExit(
            RuleGroup("OR", listOf(cond)), candles, 5, entryPrice = 100.0, currentPrice = 90.0,
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `exit OR group fires if either profit-take or stop-loss condition is met`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val takeProfitCond = RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = 8.0)
        val stopLossCond   = RuleCondition(indicator = "LOSS_RATE", comparator = "LTE", value = -4.0)
        val exitGroup = RuleGroup("OR", listOf(takeProfitCond, stopLossCond))

        val stopLossTriggered = RuleEvaluator.evaluateExit(exitGroup, candles, 5, entryPrice = 100.0, currentPrice = 95.0)
        val takeProfitTriggered = RuleEvaluator.evaluateExit(exitGroup, candles, 5, entryPrice = 100.0, currentPrice = 109.0)
        val neitherTriggered = RuleEvaluator.evaluateExit(exitGroup, candles, 5, entryPrice = 100.0, currentPrice = 102.0)

        assertThat(stopLossTriggered).isTrue()
        assertThat(takeProfitTriggered).isTrue()
        assertThat(neitherTriggered).isFalse()
    }

    @Test
    fun `exit evaluation with zero entry price never fires for profit-loss indicators`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val cond = RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = 0.0)

        val result = RuleEvaluator.evaluateExit(
            RuleGroup("OR", listOf(cond)), candles, 5, entryPrice = 0.0, currentPrice = 100.0,
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `exit evaluation delegates non profit-loss indicators back to entry-style evaluation`() {
        // RSI as an exit condition should reuse evaluateEntryCondition under the hood
        val candles = (0..20).map { candle(it, 100.0 + it) }
        val cond = RuleCondition(indicator = "RSI", comparator = "GTE", params = mapOf("period" to 14), value = 50.0)

        val result = RuleEvaluator.evaluateExit(
            RuleGroup("OR", listOf(cond)), candles, 20, entryPrice = 100.0, currentPrice = 120.0,
        )

        assertThat(result).isTrue()
    }
}
