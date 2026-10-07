package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDate

class MarketSignalQueryServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val ruleSetRepository = mockk<RuleSetRepository>()
    private val service = MarketSignalQueryService(jdbc, ruleSetRepository)

    private fun stubMarket(creatorId: Long) {
        every {
            jdbc.query("SELECT ruleset_id, user_id FROM strategy_market WHERE id = ?", any<RowMapper<Pair<String, Long>>>(), 7L)
        } returns listOf("rs1" to creatorId)
    }

    private fun stubSubscribed(userId: Long, subscribed: Boolean) {
        every {
            jdbc.queryForObject("SELECT COUNT(*) FROM strategy_subscriptions WHERE market_id = ? AND user_id = ?", Long::class.java, 7L, userId)
        } returns if (subscribed) 1L else 0L
    }

    private fun stubHistory() {
        every { jdbc.query(match<String> { it.contains("FROM quant_signals WHERE rule_set_id = ?") }, any<RowMapper<Any>>(), "rs1", any<Int>()) } returns emptyList()
        every { ruleSetRepository.findAllById(any<Iterable<String>>()) } returns emptyList()
    }

    @Test
    fun `strategy history is denied to a user who neither owns nor subscribes`() {
        stubMarket(creatorId = 1L)
        stubSubscribed(userId = 9L, subscribed = false)

        assertThrows<AccessDeniedException> { service.strategyHistory(9L, 7L, 30) }
        verify(exactly = 0) { jdbc.query(match<String> { it.contains("FROM quant_signals") }, any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `strategy history is allowed for a subscriber`() {
        stubMarket(creatorId = 1L)
        stubSubscribed(userId = 9L, subscribed = true)
        stubHistory()

        assertThat(service.strategyHistory(9L, 7L, 30)).isEmpty()
    }

    @Test
    fun `strategy history is allowed for the creator without a subscription lookup`() {
        stubMarket(creatorId = 1L)
        stubHistory()

        service.strategyHistory(1L, 7L, 30)

        verify(exactly = 0) { jdbc.queryForObject(match<String> { it.contains("strategy_subscriptions") }, Long::class.java, *anyVararg()) }
    }

    @Test
    fun `unknown market is not found`() {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, Long>>>(), 7L) } returns emptyList()

        assertThrows<NoSuchElementException> { service.strategyHistory(1L, 7L, 30) }
    }

    @Test
    fun `subscribed feed is scoped to the caller's subscriptions and caps the limit`() {
        every { jdbc.query(match<String> { it.contains("JOIN strategy_subscriptions ss ON ss.market_id = sm.id AND ss.user_id = ?") }, any<RowMapper<Any>>(), 9L, 100) } returns emptyList()
        every { jdbc.queryForObject(match<String> { it.contains("COUNT(*) FROM quant_signals") }, Long::class.java, 9L, any()) } returns 4L
        every { ruleSetRepository.findAllById(any<Iterable<String>>()) } returns emptyList()

        val feed = service.subscribedFeed(9L, limit = 10_000, today = LocalDate.of(2026, 10, 6))

        assertThat(feed.thisMonthCount).isEqualTo(4L)
        verify { jdbc.query(any<String>(), any<RowMapper<Any>>(), 9L, 100) }
    }
}
