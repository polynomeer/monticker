package com.monticker.api.watchrule.application

import com.monticker.api.wallet.equity.PaperEquityQuery
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleOrderType
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.domain.WatchRuleSizeType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** ADR-095 — 발동 시점 주문 계획(지정가·계좌 % 수량). */
class WatchRuleOrderPlannerTest {
    private val now = Instant.parse("2026-10-08T01:00:00Z")
    private val targets = mockk<WatchRuleTargets>()
    private val equity = mockk<PaperEquityQuery>()
    private val planner = WatchRuleOrderPlanner(targets, equity, Clock.fixed(now, ZoneOffset.UTC))

    private fun rule(
        orderType: WatchRuleOrderType = WatchRuleOrderType.MARKET,
        offset: Int? = null,
        sizeType: WatchRuleSizeType = WatchRuleSizeType.SHARES,
        quantity: Int? = 3,
        pct: String? = null,
    ) = WatchRule(
        id = 1L, userId = 7L, stockId = 100L, eventType = "VOLUME_SURGE", side = WatchRuleSide.BUY, quantity = quantity,
        orderType = orderType, limitOffsetBps = offset, sizeType = sizeType, equityPct = pct?.let(::BigDecimal),
    )

    private fun price(close: String, ageSec: Long = 30) {
        every { targets.latestPrice(100L) } returns LatestPrice(BigDecimal(close), now.minusSeconds(ageSec))
    }

    @Test
    fun `a market share rule needs no price or equity`() {
        assertThat(planner.plan(rule(), 100L)).isEqualTo(OrderPlan.Ready(3, null))
        verify(exactly = 0) { targets.latestPrice(any()) }
        verify(exactly = 0) { equity.paperEquity(any()) }
    }

    @Test
    fun `a limit share rule prices off the latest close`() {
        price("70000")
        assertThat(planner.plan(rule(WatchRuleOrderType.LIMIT, offset = -50), 100L))
            .isEqualTo(OrderPlan.Ready(3, BigDecimal("69650")))
    }

    @Test
    fun `an equity percent rule sizes from the wallet equity and the limit price`() {
        price("70000")
        every { equity.paperEquity(7L) } returns BigDecimal("10000000")
        // 지정가 69,650 기준: 500,000 ÷ 69,650 = 7.17 → 7주
        assertThat(planner.plan(rule(WatchRuleOrderType.LIMIT, offset = -50, sizeType = WatchRuleSizeType.EQUITY_PCT, quantity = null, pct = "5"), 100L))
            .isEqualTo(OrderPlan.Ready(7, BigDecimal("69650")))
    }

    @Test
    fun `an equity percent rule that rounds to zero is skipped with the amounts`() {
        price("700000")
        every { equity.paperEquity(7L) } returns BigDecimal("1000000")
        val plan = planner.plan(rule(sizeType = WatchRuleSizeType.EQUITY_PCT, quantity = null, pct = "1"), 100L)
        assertThat(plan).isInstanceOf(OrderPlan.Skip::class.java)
        assertThat((plan as OrderPlan.Skip).reason).contains("0주").contains("1%").contains("10,000원")
    }

    @Test
    fun `a stale or missing price skips instead of pricing off an old quote`() {
        price("70000", ageSec = 600)
        assertThat(planner.plan(rule(WatchRuleOrderType.LIMIT, offset = 0), 100L)).isInstanceOf(OrderPlan.Skip::class.java)
        every { targets.latestPrice(100L) } returns null
        assertThat(planner.plan(rule(sizeType = WatchRuleSizeType.EQUITY_PCT, quantity = null, pct = "5"), 100L))
            .isInstanceOf(OrderPlan.Skip::class.java)
    }
}
