package com.monticker.api.analytics.application

import com.monticker.api.quant.domain.QuantBacktestResult
import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PositionSizerServiceTest {

    private val backtestResultRepository = mockk<QuantBacktestResultRepository>()
    private val service = PositionSizerService(backtestResultRepository)

    // ── kellyFraction (pure formula) ────────────────────────────────────────────

    @Test
    fun `kellyFraction is positive when edge is favourable`() {
        // 60% win rate, 2 to 1 win-loss ratio: f = (b*p - q)/b = (2*0.6 - 0.4)/2 = 0.4
        val f = service.kellyFraction(winRate = 0.6, avgWin = 2.0, avgLoss = 1.0)

        assertThat(f).isCloseTo(0.4, within(0.0001))
    }

    @Test
    fun `kellyFraction is zero or negative clamped to zero when edge is unfavourable`() {
        // 30% win rate, 1 to 1 ratio: f = (1*0.3 - 0.7)/1 = -0.4 -> clamped to 0
        val f = service.kellyFraction(winRate = 0.3, avgWin = 1.0, avgLoss = 1.0)

        assertThat(f).isEqualTo(0.0)
    }

    @Test
    fun `kellyFraction returns zero when avgLoss is zero or negative`() {
        assertThat(service.kellyFraction(winRate = 0.6, avgWin = 2.0, avgLoss = 0.0)).isEqualTo(0.0)
        assertThat(service.kellyFraction(winRate = 0.6, avgWin = 2.0, avgLoss = -1.0)).isEqualTo(0.0)
    }

    @Test
    fun `kellyFraction is capped at one even for an extremely favourable edge`() {
        val f = service.kellyFraction(winRate = 0.99, avgWin = 100.0, avgLoss = 0.01)

        assertThat(f).isLessThanOrEqualTo(1.0)
    }

    // ── calculateKelly ───────────────────────────────────────────────────────────

    @Test
    fun `calculateKelly halves the full Kelly fraction for the recommended size`() {
        val result = service.calculateKelly(winRate = 0.6, avgWinPct = 2.0, avgLossPct = 1.0)

        assertThat(result.halfKelly).isCloseTo(result.fullKelly / 2.0, within(0.0001))
    }

    @Test
    fun `calculateKelly recommends against trading when the fraction is zero`() {
        val result = service.calculateKelly(winRate = 0.2, avgWinPct = 1.0, avgLossPct = 1.0)

        assertThat(result.fullKelly).isEqualTo(0.0)
        assertThat(result.recommendation).contains("포지션을 잡지 않는 것을 권장")
    }

    @Test
    fun `calculateKelly gives a conservative recommendation for a very small positive fraction`() {
        // pick numbers producing 0 < f < 0.05
        val result = service.calculateKelly(winRate = 0.5, avgWinPct = 1.05, avgLossPct = 1.0)

        assertThat(result.fullKelly).isGreaterThan(0.0).isLessThan(0.05)
        assertThat(result.recommendation).contains("매우 낮습니다")
    }

    @Test
    fun `calculateKelly warns about volatility when the fraction exceeds 0_5`() {
        val result = service.calculateKelly(winRate = 0.9, avgWinPct = 5.0, avgLossPct = 1.0)

        assertThat(result.fullKelly).isGreaterThan(0.5)
        assertThat(result.recommendation).contains("변동성 위험")
    }

    @Test
    fun `calculateKelly gives the standard half-Kelly recommendation for a moderate fraction`() {
        val result = service.calculateKelly(winRate = 0.55, avgWinPct = 1.5, avgLossPct = 1.0)

        assertThat(result.fullKelly).isBetween(0.05, 0.5)
        assertThat(result.recommendation).contains("권장 포지션 크기")
    }

    // ── calculateKellyForRuleSet ─────────────────────────────────────────────────

    private fun backtestResult(winRate: BigDecimal?, profitFactor: BigDecimal?, createdAt: java.time.Instant) =
        QuantBacktestResult(
            ruleSetId = "1", ruleSetVersion = 1, stockId = 100L,
            startDate = LocalDate.of(2025, 1, 1), endDate = LocalDate.of(2025, 12, 31),
            initialCapital = BigDecimal("10000000"), finalCapital = BigDecimal("11000000"),
            winRate = winRate, profitFactor = profitFactor, createdAt = createdAt,
        )

    @Test
    fun `calculateKellyForRuleSet returns a no-data recommendation when there are no backtest results`() {
        every { backtestResultRepository.findAllByRuleSetId("1") } returns emptyList()

        val result = service.calculateKellyForRuleSet("1")

        assertThat(result.recommendation).contains("백테스트 결과가 없습니다")
        assertThat(result.fullKelly).isEqualTo(0.0)
    }

    @Test
    fun `calculateKellyForRuleSet uses the most recently created backtest result`() {
        val older = backtestResult(BigDecimal("40"), BigDecimal("1.0"), java.time.Instant.now().minusSeconds(120))
        val newer = backtestResult(BigDecimal("60"), BigDecimal("2.0"), java.time.Instant.now())
        every { backtestResultRepository.findAllByRuleSetId("1") } returns listOf(older, newer)

        val result = service.calculateKellyForRuleSet("1")

        // winRate 60% should be used (from the newer result), not 40%
        assertThat(result.winRate).isCloseTo(0.6, within(0.0001))
    }

    @Test
    fun `calculateKellyForRuleSet derives avgWin from profitFactor and winRate`() {
        // profitFactor = (winRate * avgWin) / ((1-winRate) * avgLoss), avgLoss=1
        // winRate=0.5, profitFactor=2.0 -> avgWin = (2.0 * 0.5 * 1) / 0.5 = 2.0
        val result = backtestResult(BigDecimal("50"), BigDecimal("2.0"), java.time.Instant.now())
        every { backtestResultRepository.findAllByRuleSetId("1") } returns listOf(result)

        val kelly = service.calculateKellyForRuleSet("1")

        assertThat(kelly.avgWin).isCloseTo(2.0, within(0.0001))
        assertThat(kelly.avgLoss).isEqualTo(1.0)
    }

    @Test
    fun `calculateKellyForRuleSet falls back to a fixed avgWin ratio when profitFactor is missing`() {
        val result = backtestResult(BigDecimal("55"), null, java.time.Instant.now())
        every { backtestResultRepository.findAllByRuleSetId("1") } returns listOf(result)

        val kelly = service.calculateKellyForRuleSet("1")

        assertThat(kelly.avgWin).isCloseTo(1.5, within(0.0001))
    }

    @Test
    fun `calculateKellyForRuleSet treats a missing winRate as zero`() {
        val result = backtestResult(null, BigDecimal("2.0"), java.time.Instant.now())
        every { backtestResultRepository.findAllByRuleSetId("1") } returns listOf(result)

        val kelly = service.calculateKellyForRuleSet("1")

        assertThat(kelly.winRate).isEqualTo(0.0)
        assertThat(kelly.fullKelly).isEqualTo(0.0)
    }
}
