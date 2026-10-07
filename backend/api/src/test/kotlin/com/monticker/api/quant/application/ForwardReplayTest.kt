package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.PositionSizing
import com.monticker.api.quant.domain.RuleCondition
import com.monticker.api.quant.domain.RuleDefinition
import com.monticker.api.quant.domain.RuleGroup
import com.monticker.api.quant.domain.SignalDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ForwardReplayTest {

    private val day0 = LocalDate.of(2026, 3, 2)

    private fun candle(day: Int, close: Double) = DailyCandle(
        date = day0.plusDays(day.toLong()), open = BigDecimal(close), high = BigDecimal(close),
        low = BigDecimal(close), close = BigDecimal(close), volume = 1000L,
    )

    /** 진입 항상, 다음 날 항상 청산 — 하루 한 가지 행동이므로 BUY/SELL이 번갈아 나온다. */
    private val alternating = RuleDefinition(
        entryRules = RuleGroup("OR", listOf(RuleCondition("CLOSE_VS_MA", "GTE", mapOf("period" to 1)))),
        exitRules  = RuleGroup("OR", listOf(RuleCondition("PROFIT_RATE", "GTE", value = -999.0))),
        positionSizing = PositionSizing("FIXED_RATIO", 50.0),
    )

    @Test
    fun `replay alternates buy and sell one action per day within the window`() {
        val candles = (0..5).map { candle(it, 100.0) }

        val signals = ForwardReplay.replay(candles, alternating, 1_000_000.0, day0.plusDays(1), day0.plusDays(4))

        assertThat(signals.map { it.direction }).containsExactly(
            SignalDirection.BUY, SignalDirection.SELL, SignalDirection.BUY, SignalDirection.SELL,
        )
        assertThat(signals.first().date).isEqualTo(day0.plusDays(1))
        assertThat(signals.last().date).isEqualTo(day0.plusDays(4))
    }

    @Test
    fun `replay uses the same lookback window as the daily forward evaluation`() {
        // period=2 이동평균은 창에 캔들이 2개 이상 있어야 계산된다. 창을 1일로 줄이면 진입이 일어나지 않는다.
        val needsTwo = alternating.copy(
            entryRules = RuleGroup("OR", listOf(RuleCondition("CLOSE_VS_MA", "GTE", mapOf("period" to 2)))),
        )
        val candles = (0..3).map { candle(it, 100.0) }

        assertThat(ForwardReplay.replay(candles, needsTwo, 1_000_000.0, day0.plusDays(1), day0.plusDays(1), lookbackDays = 0)).isEmpty()
        assertThat(ForwardReplay.replay(candles, needsTwo, 1_000_000.0, day0.plusDays(1), day0.plusDays(1), lookbackDays = 1)).hasSize(1)
    }

    @Test
    fun `compare is the jaccard index over date and direction pairs`() {
        val a = listOf(ReplaySignal(day0, SignalDirection.BUY), ReplaySignal(day0.plusDays(3), SignalDirection.SELL))
        val b = listOf(ReplaySignal(day0, SignalDirection.BUY), ReplaySignal(day0.plusDays(4), SignalDirection.SELL))

        val match = ForwardReplay.compare(a, b)

        assertThat(match.matched).isEqualTo(1)
        assertThat(match.compared).isEqualTo(3)
        assertThat(match.rate).isEqualTo(1.0 / 3)
    }

    @Test
    fun `compare reports no rate when neither side has any signal`() {
        assertThat(ForwardReplay.compare(emptyList(), emptyList()).rate).isNull()
    }

    @Test
    fun `first evaluation date is the start day only when started before the 16h KST run`() {
        // 2026-03-02 15:59 KST = 06:59Z, 16:00 KST = 07:00Z
        assertThat(ForwardReplay.firstEvaluationDate(Instant.parse("2026-03-02T06:59:00Z"))).isEqualTo(day0)
        assertThat(ForwardReplay.firstEvaluationDate(Instant.parse("2026-03-02T07:00:00Z"))).isEqualTo(day0.plusDays(1))
    }
}
