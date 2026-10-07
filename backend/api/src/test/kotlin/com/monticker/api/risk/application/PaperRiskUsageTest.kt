package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PaperRiskUsageTest {

    private val rules = mockk<RiskRuleQueryService>()
    private val usage = PaperRiskUsage(rules)
    private val limits = RiskLimit(userId = 1L) // 일손실 3%, 집중도 30%, VaR 5%

    @Test
    fun `게이트와 같은 기준으로 사용률을 낸다`() {
        every { rules.paperSnapshot(1L) } returns PortfolioSnapshot(
            cash = BigDecimal("6000000"),
            holdings = listOf(HoldingPosition(10L, 100), HoldingPosition(20L, 10)),
            dailyPnl = BigDecimal("-150000"),
            recentOrderCount = 0,
        )
        every { rules.historicalVaRPct(listOf(10L, 20L)) } returns 4.5
        every { rules.currentPrice(10L) } returns BigDecimal("30000") // 3,000,000
        every { rules.currentPrice(20L) } returns BigDecimal("100000") // 1,000,000
        every { rules.symbolOf(10L) } returns "005930"

        val byRule = usage.evaluate(1L, limits).associateBy { it.rule }

        assertThat(byRule["DAILY_LOSS"]!!.ratio).isCloseTo(150000.0 / 6000000 * 100 / 3.0, within(1e-9)) // 2.5% / 3%
        assertThat(byRule["VAR"]!!.ratio).isCloseTo(0.9, within(1e-9))
        assertThat(byRule["CONCENTRATION"]!!.current).isCloseTo(30.0, within(1e-9)) // 3M / 10M
        assertThat(byRule["CONCENTRATION"]!!.subject).isEqualTo("005930")
    }

    @Test
    fun `가격을 모르는 보유가 있으면 집중도는 판정하지 않는다`() {
        every { rules.paperSnapshot(1L) } returns PortfolioSnapshot(
            cash = BigDecimal("1000"), holdings = listOf(HoldingPosition(10L, 1)), dailyPnl = BigDecimal.ZERO, recentOrderCount = 0,
        )
        every { rules.historicalVaRPct(any()) } returns 0.0
        every { rules.currentPrice(10L) } returns BigDecimal.ZERO

        val rulesOut = usage.evaluate(1L, limits).map { it.rule }

        assertThat(rulesOut).containsExactly("VAR")
    }
}
