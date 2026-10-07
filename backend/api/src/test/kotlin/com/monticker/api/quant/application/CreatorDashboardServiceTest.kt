package com.monticker.api.quant.application

import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import com.monticker.api.settlement.creator.application.MonthlyNet
import com.monticker.api.settlement.creator.application.StrategyNet
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

class CreatorDashboardServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val earnings = mockk<CreatorEarningsService>()
    private val ruleSets = mockk<RuleSetRepository>()
    private val service = CreatorDashboardService(jdbc, earnings, ruleSets)

    private fun marketRow(id: Long, rulesetId: String, subscribers: Long): ResultSet = mockk {
        every { getLong("id") } returns id
        every { getString("ruleset_id") } returns rulesetId
        every { getBigDecimal("price") } returns BigDecimal.ZERO
        every { getTimestamp("created_at") } returns Timestamp.from(Instant.parse("2026-05-01T00:00:00Z"))
        every { getLong("subscribers") } returns subscribers
    }

    @Test
    fun `dashboard fills twelve KST months, sums this month and names each strategy`() {
        val today = LocalDate.of(2026, 10, 6)
        val since = slot<Instant>()
        every { earnings.getMonthlyNet(1L, capture(since)) } returns listOf(
            MonthlyNet("2026-08", BigDecimal("700")), MonthlyNet("2026-10", BigDecimal("1400")),
        )
        every { earnings.getStrategyNet(1L, any()) } returns mapOf(
            10L to StrategyNet(total = BigDecimal("2100"), sinceNet = BigDecimal("1400")),
        )
        val mapper = slot<RowMapper<Any>>()
        every { jdbc.query(match<String> { it.contains("FROM strategy_market sm") }, capture(mapper), 1L) } answers {
            listOf(mapper.captured.mapRow(marketRow(10L, "rsA", 3), 0), mapper.captured.mapRow(marketRow(11L, "rsB", 2), 1))
        }
        every { ruleSets.findAllById(listOf("rsA", "rsB")) } returns listOf(RuleSetDocument(id = "rsA", userId = 1L, name = "모멘텀"))

        val d = service.dashboard(1L, today)

        assertThat(d.monthly).hasSize(12)
        assertThat(d.monthly.first().month).isEqualTo("2025-11")
        assertThat(d.monthly.last()).isEqualTo(MonthlyNetPoint("2026-10", BigDecimal("1400")))
        assertThat(d.monthly.first { it.month == "2026-09" }.net).isEqualByComparingTo("0")
        // 2025-11-01 00:00 KST
        assertThat(since.captured).isEqualTo(Instant.parse("2025-10-31T15:00:00Z"))
        assertThat(d.thisMonthNet).isEqualByComparingTo("1400")
        assertThat(d.totalNet).isEqualByComparingTo("2100")
        assertThat(d.activeSubscribers).isEqualTo(5)
        assertThat(d.strategies.map { it.name }).containsExactly("모멘텀", "(삭제된 전략)")
        assertThat(d.strategies.last().totalNet).isEqualByComparingTo("0")
    }
}
