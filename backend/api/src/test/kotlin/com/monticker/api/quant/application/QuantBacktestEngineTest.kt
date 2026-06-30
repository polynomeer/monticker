package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.PositionSizing
import com.monticker.api.quant.domain.RuleCondition
import com.monticker.api.quant.domain.RuleDefinition
import com.monticker.api.quant.domain.RuleGroup
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class QuantBacktestEngineTest {

    private fun candle(day: Int, close: Double, volume: Long = 1000L) = DailyCandle(
        date = LocalDate.of(2024, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close),
        high = BigDecimal(close),
        low = BigDecimal(close),
        close = BigDecimal(close),
        volume = volume,
    )

    // NOTE: PROFIT_RATE / LOSS_RATE are exit-only indicators — RuleEvaluator's
    // evaluateEntryCondition() does not recognise them and always returns false
    // for them on the entry side. An "always true" entry must use an
    // entry-side indicator instead, e.g. CLOSE_VS_MA with period=1 (MA of a
    // single candle equals its own close, so close >= MA is always true).
    private fun alwaysTrueEntryCondition() =
        RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GTE", params = mapOf("period" to 1))

    private fun neverTrueEntryCondition() =
        RuleCondition(indicator = "CLOSE_VS_MA", comparator = "GT", params = mapOf("period" to 1), value = 999_999.0)

    /** Entry always fires; exit only via PROFIT_RATE, fires on essentially any holding period. */
    private fun alwaysEntryAlwaysExitRule() = RuleDefinition(
        entryRules = RuleGroup("OR", listOf(alwaysTrueEntryCondition())),
        exitRules  = RuleGroup("OR", listOf(RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = -999.0))),
        positionSizing = PositionSizing("FIXED_RATIO", 50.0),
    )

    /** Entry always fires; exit threshold is unreachable so the only trade is the final force-close. */
    private fun alwaysEntryUnreachableExitRule(ratio: Double = 50.0) = RuleDefinition(
        entryRules = RuleGroup("OR", listOf(alwaysTrueEntryCondition())),
        exitRules  = RuleGroup("OR", listOf(RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = 999_999.0))),
        positionSizing = PositionSizing("FIXED_RATIO", ratio),
    )

    private fun neverEntryRule() = RuleDefinition(
        entryRules = RuleGroup("AND", listOf(neverTrueEntryCondition())),
        exitRules  = RuleGroup("OR", listOf(RuleCondition(indicator = "PROFIT_RATE", comparator = "GTE", value = 8.0))),
        positionSizing = PositionSizing("FIXED_RATIO", 10.0),
    )

    @Test
    fun `run returns empty result when no candles fall within the requested date range`() {
        val candles = (0..10).map { candle(it, 100.0) }
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = LocalDate.of(2030, 1, 1), toDate = LocalDate.of(2030, 12, 31),
        )

        assertThat(result.finalCapital).isEqualTo(1_000_000.0)
        assertThat(result.trades).isEmpty()
        assertThat(result.metrics.reliabilityScore).isEqualTo("D")
    }

    @Test
    fun `run produces no trades and unchanged capital when entry condition never fires`() {
        val candles = (0..30).map { candle(it, 100.0 + it) }
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.trades).isEmpty()
        assertThat(result.finalCapital).isEqualTo(1_000_000.0)
        assertThat(result.metrics.totalReturn).isEqualTo(0.0)
    }

    @Test
    fun `run executes a buy then force-closes the open position at the end of the period`() {
        // Entry fires immediately (always-true), exit threshold is unreachable,
        // so the position is force-closed ("END") on the last candle.
        val ruleDef = alwaysEntryUnreachableExitRule()
        val candles = (0..10).map { candle(it, 100.0) }

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.trades).hasSize(1)
        assertThat(result.trades.first().exitReason).isEqualTo("END")
    }

    @Test
    fun `run applies commission and slippage so buy price exceeds quoted close and sell price is below it`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val ruleDef = alwaysEntryAlwaysExitRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.trades).isNotEmpty()
        val firstTrade = result.trades.first()
        // entryPrice should reflect a markup over the raw 100.0 close (slippage)
        assertThat(firstTrade.entryPrice).isGreaterThan(100.0)
        assertThat(firstTrade.exitPrice).isLessThan(100.0)
    }

    @Test
    fun `run never lets cash go negative when sizing a position`() {
        val candles = (0..20).map { candle(it, 100_000.0) }
        val ruleDef = alwaysEntryUnreachableExitRule(ratio = 100.0)

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.finalCapital).isGreaterThanOrEqualTo(0.0)
    }

    @Test
    fun `reliability score is D for a small number of trades`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.metrics.reliabilityScore).isEqualTo("D")
    }

    @Test
    fun `reliability score is C when trade count reaches 10 regardless of date range`() {
        // Force exactly 10 buy/exit cycles using an always-true entry/exit alternating rule.
        val candles = (0..40).map { candle(it, 100.0) }
        val ruleDef = alwaysEntryAlwaysExitRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.trades.size).isGreaterThanOrEqualTo(10)
        assertThat(result.metrics.reliabilityScore).isIn("C", "B", "A")
    }

    @Test
    fun `benchmark return is computed as buy-and-hold from first open to last close`() {
        val candles = (0..10).map { candle(it, 100.0 + it * 5) } // 100 -> 150
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        // (150 - 100) / 100 * 100 = 50%
        assertThat(result.metrics.benchmarkReturn).isCloseTo(50.0, within(0.1))
    }

    @Test
    fun `excess return equals total return minus benchmark return`() {
        val candles = (0..10).map { candle(it, 100.0 + it) }
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.metrics.excessReturn)
            .isCloseTo(result.metrics.totalReturn - result.metrics.benchmarkReturn, within(0.0001))
    }

    @Test
    fun `mdd is the maximum drawdown observed across the equity curve`() {
        val candles = (0..10).map { candle(it, 100.0) }
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        // No trades occur, equity never drops below initial capital -> 0 drawdown
        assertThat(result.metrics.mdd).isEqualTo(0.0)
    }

    @Test
    fun `win rate and profit factor are zero when there are no trades`() {
        val candles = (0..10).map { candle(it, 100.0) }
        val ruleDef = neverEntryRule()

        val result = QuantBacktestEngine.run(
            candles, ruleDef, initialCapital = 1_000_000.0,
            fromDate = candles.first().date, toDate = candles.last().date,
        )

        assertThat(result.metrics.winRate).isEqualTo(0.0)
        assertThat(result.metrics.profitFactor).isEqualTo(0.0)
        assertThat(result.metrics.tradeCount).isEqualTo(0)
    }
}
