package com.monticker.api.wallet.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** ADR-091 — 점수 카드 세부 지표의 판정 규칙. SQL은 ScoreDetailIntegrationTest. */
class ScoreDetailMetricsTest {

    private val t = Instant.parse("2026-10-06T02:00:00Z")

    private fun exit(price: String, since: Instant? = null, origin: String? = "MANUAL", ref: Long? = null, stock: Long = 1, at: Instant = t) =
        LosingExit(tradeId = 1, stockId = stock, price = BigDecimal(price), tradedAt = at, positionSince = since, origin = origin, originRef = ref)

    private fun stop(id: Long, price: String, createdAt: Instant, stock: Long = 1) = StopLossDef(id, stock, BigDecimal(price), createdAt)

    // ── Ratio ──

    @Test
    fun `ratio with a zero denominator has no percentage`() {
        assertThat(Ratio(0, 0).pct).isNull()
        assertThat(Ratio.of(listOf(null, null)).pct).isNull()
        assertThat(Ratio.of(emptyList()).denominator).isZero()
    }

    @Test
    fun `ratio counts true over non-null`() {
        val r = Ratio.of(listOf(true, false, null, true))
        assertThat(r).isEqualTo(Ratio(2, 3))
        assertThat(r.pct!!).isCloseTo(66.666, org.assertj.core.api.Assertions.within(0.001))
    }

    @Test
    fun `week over week delta is null when either week has no denominator`() {
        assertThat(WeeklyRatio(Ratio(3, 4), Ratio(1, 2)).deltaPp).isEqualTo(25.0)
        assertThat(WeeklyRatio(Ratio(3, 4), Ratio(0, 0)).deltaPp).isNull()
        assertThat(WeeklyRatio(Ratio(0, 0), Ratio(1, 2)).deltaPp).isNull()
        assertThat(WeeklyScore(80.0, 70.0, 3, 5).delta).isEqualTo(10.0)
        assertThat(WeeklyScore(80.0, null, 3, 0).delta).isNull()
    }

    // ── 계획 준수율은 리플레이와 같은 함수 ──

    @Test
    fun `plan adherence reuses the replay planned rule`() {
        val flags = listOf(
            ReplayService.isPlanned("WATCH_RULE", null),   // true
            ReplayService.isPlanned("MANUAL", "PLANNED"),  // true
            ReplayService.isPlanned("MANUAL", "FOMO"),     // false
            ReplayService.isPlanned(null, null),           // 판정 불가
        )
        assertThat(Ratio.of(flags)).isEqualTo(Ratio(2, 3))
    }

    // ── 손절 준수율 ──

    @Test
    fun `a losing exit with no stop defined for the position is excluded`() {
        assertThat(StopLossAdherence.respected(exit("90"), emptyList())).isNull()
        // 다른 종목의 손절, 매도 이후에 만든 손절, 이전 포지션(마지막으로 보유가 0이 된 시각 이전)의 손절은 이 포지션의 손절이 아니다
        val since = t.minusSeconds(3600)
        val stops = listOf(
            stop(1, "95", t.minusSeconds(60), stock = 2),
            stop(2, "95", t.plusSeconds(1)),
            stop(3, "95", since.minusSeconds(1)),
        )
        assertThat(StopLossAdherence.respected(exit("90", since = since), stops)).isNull()
        assertThat(StopLossAdherence.ratio(listOf(exit("90", since = since)), stops).pct).isNull()
    }

    @Test
    fun `exiting at or above the stop price respects it, below does not`() {
        val stops = listOf(stop(1, "95", t.minusSeconds(60)))
        assertThat(StopLossAdherence.respected(exit("95"), stops)).isTrue()
        assertThat(StopLossAdherence.respected(exit("97"), stops)).isTrue()
        assertThat(StopLossAdherence.respected(exit("94.99"), stops)).isFalse()
    }

    @Test
    fun `the stop firing itself respects it even when the fill gapped below the trigger`() {
        val stops = listOf(stop(7, "95", t.minusSeconds(60)))
        assertThat(StopLossAdherence.respected(exit("90", origin = "CONDITIONAL", ref = 7), stops)).isTrue()
        // 다른 조건부 주문(예: 가격 도달 매도)이 손절가 아래에서 판 것은 지킨 것이 아니다
        assertThat(StopLossAdherence.respected(exit("90", origin = "CONDITIONAL", ref = 8), stops)).isFalse()
    }

    @Test
    fun `the reference is the first stop set for the position - lowering it later does not move the line`() {
        val since = t.minusSeconds(7200)
        val original = stop(1, "95", t.minusSeconds(3600))
        val lowered = stop(2, "85", t.minusSeconds(600))
        assertThat(StopLossAdherence.referenceStop(exit("90", since = since), listOf(lowered, original))).isEqualTo(original)
        // 낮춘 손절이 발동해 90에 나갔어도 처음 손절 95를 지키지 못했다
        assertThat(StopLossAdherence.respected(exit("88", since = since, origin = "CONDITIONAL", ref = 2), listOf(original, lowered))).isFalse()
        // 손절을 올려(97) 그 가격에 나간 것은 지킨 것이다
        val raised = stop(3, "97", t.minusSeconds(300))
        assertThat(StopLossAdherence.respected(exit("97", since = since, origin = "CONDITIONAL", ref = 3), listOf(original, raised))).isTrue()
    }

    @Test
    fun `a bracket registered with the opening buy (before the buy filled) belongs to the position`() {
        val flatAt = t.minusSeconds(86_400)
        val bracket = stop(1, "95", flatAt.plusSeconds(10))
        assertThat(StopLossAdherence.respected(exit("96", since = flatAt), listOf(bracket))).isTrue()
    }
}
