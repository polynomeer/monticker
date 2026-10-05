package com.monticker.api.quant.application

import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant

class QuantSignalFeedServiceTest {

    private val ruleSets = mockk<RuleSetRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = QuantSignalFeedService(ruleSets, jdbc)

    private fun doc(id: String, user: Long, name: String) = RuleSetDocument(id = id, userId = user, name = name)

    private fun stubSubscribed(vararg ids: String) {
        every { jdbc.queryForList(match<String> { it.contains("strategy_subscriptions") }, String::class.java, *anyVararg()) } returns ids.toList()
    }

    @Test
    fun `accessible rulesets are my own plus subscribed market strategies only`() {
        every { ruleSets.findAllByUserId(1L) } returns listOf(doc("mine1", 1, "내 전략"))
        stubSubscribed("sub1", "mine1")
        every { ruleSets.findAllById(listOf("sub1")) } returns listOf(doc("sub1", 2, "구독 전략"))

        val result = service.accessibleRuleSets(1L)

        assertThat(result.keys).containsExactlyInAnyOrder("mine1", "sub1")
        assertThat(result["mine1"]!!.second).isEqualTo(QuantSignalFeedService.Source.MINE)
        assertThat(result["sub1"]).isEqualTo("구독 전략" to QuantSignalFeedService.Source.SUBSCRIBED)
    }

    @Test
    fun `no accessible rulesets means no signal query at all`() {
        every { ruleSets.findAllByUserId(1L) } returns emptyList()
        stubSubscribed()

        assertThat(service.feed(1L, 20)).isEmpty()
        assertThat(service.stockIdsWithSignalsSince(1L, Instant.now())).isEmpty()
        verify(exactly = 0) { jdbc.query(match<String> { it.contains("quant_signals") }, any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `feed queries only accessible ruleset ids with a clamped limit`() {
        every { ruleSets.findAllByUserId(1L) } returns listOf(doc("mine1", 1, "내 전략"))
        stubSubscribed()
        every { jdbc.query(match<String> { it.contains("FROM quant_signals") }, any<RowMapper<QuantSignalFeedItem>>(), *anyVararg()) } returns emptyList()

        service.feed(1L, 10_000)

        verify { jdbc.query(match<String> { it.contains("IN (?)") }, any<RowMapper<QuantSignalFeedItem>>(), "mine1", QuantSignalFeedService.MAX_LIMIT) }
    }
}
