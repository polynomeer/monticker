package com.monticker.api.paper.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** ADR-085 — 이동평균법 실현 손익. PortfolioPositionProjection의 평균단가 규칙과 같아야 한다. */
class RealizedPnlCalculatorTest {

    private fun buy(id: Long, qty: Int, amount: String, stock: Long = 1L) = PnlTradeLine(id, stock, "BUY", qty, BigDecimal(amount))
    private fun sell(id: Long, qty: Int, amount: String, stock: Long = 1L) = PnlTradeLine(id, stock, "SELL", qty, BigDecimal(amount))

    @Test
    fun `sell pnl is proceeds minus quantity times the weighted average cost so far`() {
        // 10주 @1,000 + 10주 @2,000 → 평균 1,500. 5주를 1,800에 팔면 +1,500
        val r = RealizedPnlCalculator.compute(listOf(buy(1, 10, "10000"), buy(2, 10, "20000"), sell(3, 5, "9000")))

        assertThat(r.keys).containsExactly(3L)
        assertThat(r[3]!!.pnl).isEqualByComparingTo("1500")
        assertThat(r[3]!!.costBasis).isEqualByComparingTo("7500")
        assertThat(r[3]!!.pnlPct!!).isCloseTo(20.0, within(0.0001))
    }

    @Test
    fun `buys after a full exit start a fresh average — earlier lots do not leak in`() {
        // 첫 보유 @1,000 전량 매도 → 다시 @3,000 매수 → 매도. 단순 평균(2,000)이 아니라 3,000이 원가다
        val r = RealizedPnlCalculator.compute(listOf(
            buy(1, 1, "1000"), sell(2, 1, "1500"),
            buy(3, 1, "3000"), sell(4, 1, "2900"),
        ))

        assertThat(r[2]!!.pnl).isEqualByComparingTo("500")
        assertThat(r[4]!!.pnl).isEqualByComparingTo("-100")
    }

    @Test
    fun `a sell does not change the average cost of what remains`() {
        val r = RealizedPnlCalculator.compute(listOf(
            buy(1, 4, "4000"), sell(2, 2, "3000"), buy(3, 2, "4000"), sell(4, 4, "8000"),
        ))
        // 남은 2주 @1,000 + 2주 @2,000 → 평균 1,500. 4주를 8,000에 팔면 +2,000
        assertThat(r[2]!!.pnl).isEqualByComparingTo("1000")
        assertThat(r[4]!!.pnl).isEqualByComparingTo("2000")
    }

    @Test
    fun `stocks are tracked independently and a sell without a position is skipped`() {
        val r = RealizedPnlCalculator.compute(listOf(
            sell(1, 1, "100", stock = 2L),
            buy(2, 1, "100", stock = 1L), buy(3, 1, "500", stock = 2L), sell(4, 1, "200", stock = 1L),
        ))

        assertThat(r.keys).containsExactly(4L)
        assertThat(r[4]!!.pnl).isEqualByComparingTo("100")
    }
}
