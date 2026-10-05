package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

/** ADR-069 — SectorConcentrationRule. 실거래 경로(스냅샷에 증권사 총평가액)로 분모를 고정해 규칙만 본다. */
class SectorConcentrationRuleTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val rules = RiskRuleQueryService(jdbc)

    private val sectors = mutableMapOf<Long, String>()
    private val prices = mutableMapOf<Long, BigDecimal>()

    init {
        every { jdbc.query(match<String> { it.contains("sector IS NOT NULL") }, any<RowMapper<Pair<Long, String>>>(), *anyVararg()) } answers {
            args.drop(2).flatMap { a -> if (a is Array<*>) a.toList() else listOf(a) }.map { (it as Number).toLong() }
                .mapNotNull { id -> sectors[id]?.let { id to it } }
        }
        every { jdbc.query(match<String> { it.contains("candles_1m") }, any<RowMapper<BigDecimal>>(), *anyVararg()) } answers {
            val id = (args.drop(2).flatMap { a -> if (a is Array<*>) a.toList() else listOf(a) }.first() as Number).toLong()
            listOfNotNull(prices[id])
        }
    }

    private fun limits(sectorPct: String?) = RiskLimit(
        userId = 1L,
        concentrationLimitPct = BigDecimal("100"),
        varLimitPct = BigDecimal("100"),
        maxPositionCount = 100,
        maxHourlyOrders = 100,
        sectorConcentrationLimitPct = sectorPct?.let(::BigDecimal),
    )

    private fun snapshot(vararg holdings: Pair<Long, Int>, pending: Map<Long, Int> = emptyMap()) = PortfolioSnapshot(
        cash = BigDecimal("5000000"),
        holdings = holdings.map { HoldingPosition(it.first, it.second) },
        dailyPnl = BigDecimal.ZERO,
        recentOrderCount = 0,
        pendingBuys = pending,
        totalAssets = BigDecimal("10000000"),
    )

    private fun sectorRule(stockId: Long, qty: Int, price: String, limit: String?, snap: PortfolioSnapshot) =
        rules.evaluateWithSnapshot(stockId, "BUY", qty, BigDecimal(price), limits(limit), snap)
            .firstOrNull { it.rule == "SectorConcentrationRule" }

    @Test
    fun `한도를 설정하지 않으면 평가하지 않는다`() {
        sectors[1L] = "IT"
        assertThat(sectorRule(1L, 1, "1000", null, snapshot())).isNull()
    }

    @Test
    fun `같은 섹터 보유·진행 중 매수·이번 주문을 합쳐 한도를 넘으면 막는다`() {
        sectors += mapOf(1L to "IT", 2L to "IT", 3L to "금융", 4L to "IT")
        prices += mapOf(2L to BigDecimal("100000"), 3L to BigDecimal("100000"), 4L to BigDecimal("50000"))
        // IT: 보유 2번 20주(200만) + 대기 4번 10주(50만) + 주문 1번 10주×10만(100만) = 350만 / 1000만 = 35%
        val snap = snapshot(2L to 20, 3L to 30, pending = mapOf(4L to 10))

        val blocked = sectorRule(1L, 10, "100000", "30", snap)!!
        assertThat(blocked.passed).isFalse()
        assertThat(blocked.current).isEqualTo(35.0)

        assertThat(sectorRule(1L, 10, "100000", "40", snap)!!.passed).isTrue()
    }

    @Test
    fun `섹터 미분류 종목은 대상이 아니다`() {
        val r = sectorRule(9L, 1000, "100000", "10", snapshot())!!
        assertThat(r.passed).isTrue()
        assertThat(r.detail).contains("미분류")
    }

    @Test
    fun `같은 섹터 보유의 가격을 모르면 막는다`() {
        sectors += mapOf(1L to "IT", 2L to "IT")
        val r = sectorRule(1L, 1, "1000", "50", snapshot(2L to 5))!!
        assertThat(r.passed).isFalse()
    }

    @Test
    fun `추정가를 모르면 막는다`() {
        sectors[1L] = "IT"
        assertThat(sectorRule(1L, 1, "0", "50", snapshot())!!.passed).isFalse()
    }
}
