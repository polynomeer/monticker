package com.monticker.api.analytics.application

import com.monticker.api.quant.application.QuantBacktestResponse
import com.monticker.api.quant.application.RuleSetService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PositionSizerServiceTest {

    private val ruleSetService = mockk<RuleSetService>()
    private val service = PositionSizerService(ruleSetService)

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
    // "가장 최근 결과 선택" 로직은 RuleSetService.getLatestBacktestResult로 이동했으므로
    // 여기서는 그 결과가 주어졌을 때 Kelly 계산이 올바른지만 검증한다.

    private fun backtestResponse(winRate: Double?, profitFactor: Double?) = QuantBacktestResponse(
        id = 1L, ruleSetId = "1", ruleSetVersion = 1, stockId = 100L,
        startDate = LocalDate.of(2025, 1, 1), endDate = LocalDate.of(2025, 12, 31),
        initialCapital = 10_000_000.0, finalCapital = 11_000_000.0,
        totalReturn = null, annualReturn = null, mdd = null,
        winRate = winRate, profitFactor = profitFactor,
        tradeCount = null, avgHoldingDays = null, benchmarkReturn = null, excessReturn = null,
        reliabilityScore = null, createdAt = java.time.Instant.now().toString(),
    )

    @Test
    fun `calculateKellyForRuleSet returns a no-data recommendation when there are no backtest results`() {
        every { ruleSetService.getLatestBacktestResult("1") } returns null

        val result = service.calculateKellyForRuleSet("1")

        assertThat(result.recommendation).contains("백테스트 결과가 없습니다")
        assertThat(result.fullKelly).isEqualTo(0.0)
    }

    @Test
    fun `calculateKellyForRuleSet uses the winRate of the latest backtest result`() {
        every { ruleSetService.getLatestBacktestResult("1") } returns backtestResponse(60.0, 2.0)

        val result = service.calculateKellyForRuleSet("1")

        assertThat(result.winRate).isCloseTo(0.6, within(0.0001))
    }

    @Test
    fun `calculateKellyForRuleSet derives avgWin from profitFactor and winRate`() {
        // profitFactor = (winRate * avgWin) / ((1-winRate) * avgLoss), avgLoss=1
        // winRate=0.5, profitFactor=2.0 -> avgWin = (2.0 * 0.5 * 1) / 0.5 = 2.0
        every { ruleSetService.getLatestBacktestResult("1") } returns backtestResponse(50.0, 2.0)

        val kelly = service.calculateKellyForRuleSet("1")

        assertThat(kelly.avgWin).isCloseTo(2.0, within(0.0001))
        assertThat(kelly.avgLoss).isEqualTo(1.0)
    }

    @Test
    fun `calculateKellyForRuleSet falls back to a fixed avgWin ratio when profitFactor is missing`() {
        every { ruleSetService.getLatestBacktestResult("1") } returns backtestResponse(55.0, null)

        val kelly = service.calculateKellyForRuleSet("1")

        assertThat(kelly.avgWin).isCloseTo(1.5, within(0.0001))
    }

    @Test
    fun `calculateKellyForRuleSet treats a missing winRate as zero`() {
        every { ruleSetService.getLatestBacktestResult("1") } returns backtestResponse(null, 2.0)

        val kelly = service.calculateKellyForRuleSet("1")

        assertThat(kelly.winRate).isEqualTo(0.0)
        assertThat(kelly.fullKelly).isEqualTo(0.0)
    }
}
