package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.HardExits
import com.monticker.api.quant.domain.PositionSizing
import com.monticker.api.quant.domain.RuleCondition
import com.monticker.api.quant.domain.RuleDefinition
import com.monticker.api.quant.domain.RuleGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class HardExitsTest {

    private fun candle(day: Int, close: Double) = DailyCandle(
        date = LocalDate.of(2024, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close), high = BigDecimal(close), low = BigDecimal(close), close = BigDecimal(close), volume = 1000L,
    )

    /** 진입은 첫날 한 번(MA(1) ≥ 종가는 항상 참), 신호 청산은 절대 안 일어난다. */
    private fun rule(hard: HardExits) = RuleDefinition(
        entryRules = RuleGroup("OR", listOf(RuleCondition("CLOSE_VS_MA", "GTE", mapOf("period" to 1)))),
        exitRules  = RuleGroup("AND", listOf(RuleCondition("PROFIT_RATE", "GTE", value = 999_999.0))),
        positionSizing = PositionSizing("FIXED_RATIO", 50.0),
        hardExits = hard,
    )

    @Test
    fun `max hold closes the position after N trading days counted from the day after entry`() {
        val candles = (0..10).map { candle(it, 100.0) }

        val result = QuantBacktestEngine.run(candles, rule(HardExits(maxHoldDays = 3)), 1_000_000.0, candles.first().date, candles.last().date)

        val first = result.trades.first()
        assertThat(first.exitReason).isEqualTo("MAX_HOLD")
        assertThat(first.entryDate).isEqualTo(candles[0].date)
        assertThat(first.exitDate).isEqualTo(candles[3].date)
    }

    @Test
    fun `trailing stop fires when the close falls the given percent below the peak close since entry`() {
        val closes = listOf(100.0, 110.0, 120.0, 115.0, 107.0, 130.0)
        val candles = closes.mapIndexed { i, c -> candle(i, c) }

        val result = QuantBacktestEngine.run(candles, rule(HardExits(trailingStopPct = 10.0)), 1_000_000.0, candles.first().date, candles.last().date)

        // 고점 120 → 108 이하에서 청산: 115는 아직, 107에서 청산
        val first = result.trades.first()
        assertThat(first.exitReason).isEqualTo("TRAILING_STOP")
        assertThat(first.exitDate).isEqualTo(candles[4].date)
    }

    @Test
    fun `hard exits fire even when the exit rule group uses AND and is never satisfied`() {
        val candles = (0..5).map { candle(it, 100.0) }

        val action = QuantDayStep.decide(
            rule(HardExits(maxHoldDays = 1)), candles, 1, 0.0,
            SimPosition(qty = 1, entryPrice = 100.0, entryDate = candles[0].date),
        )

        assertThat(action).isInstanceOf(DayAction.Exit::class.java)
        assertThat((action as DayAction.Exit).reason).isEqualTo("MAX_HOLD")
    }

    @Test
    fun `no hard exits configured leaves the position alone`() {
        val candles = (0..5).map { candle(it, 100.0) }

        val action = QuantDayStep.decide(
            rule(HardExits()), candles, 5, 0.0,
            SimPosition(qty = 1, entryPrice = 100.0, entryDate = candles[0].date),
        )

        assertThat(action).isEqualTo(DayAction.Hold)
    }

    @Test
    fun `an empty AND exit group never fires on its own`() {
        val candles = (0..5).map { candle(it, 100.0) }
        val noExitRules = rule(HardExits(maxHoldDays = 4)).copy(exitRules = RuleGroup("AND", emptyList()))

        val action = QuantDayStep.decide(noExitRules, candles, 2, 0.0, SimPosition(1, 100.0, candles[0].date))

        assertThat(action).isEqualTo(DayAction.Hold)
    }
}
