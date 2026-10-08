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

    // ── 퀀트랩 상단 집계 ─────────────────────────────────────────────

    @Test
    fun `summary counts today by the KST day even when UTC is still yesterday`() {
        every { ruleSets.findAllByUserId(1L) } returns listOf(doc("mine1", 1, "내 전략"))
        stubSubscribed()
        every { jdbc.queryForObject(match<String> { it.contains("FROM quant_signals") }, Int::class.java, *anyVararg()) } returns 3
        every { jdbc.queryForObject(match<String> { it.contains("COUNT(*) FROM strategy_subscriptions") }, Int::class.java, 1L) } returns 2

        // UTC 10-07 15:30 = KST 10-08 00:30 → 오늘은 10-08, 경계는 UTC 10-07 15:00 ~ 10-08 15:00
        val r = service.summary(1L, Instant.parse("2026-10-07T15:30:00Z"))

        assertThat(r).isEqualTo(QuantSignalSummary(todaySignals = 3, activeSubscriptions = 2, date = "2026-10-08"))
        verify {
            jdbc.queryForObject(match<String> { it.contains("IN (?)") }, Int::class.java,
                "mine1",
                java.sql.Timestamp.from(Instant.parse("2026-10-07T15:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-10-08T15:00:00Z")))
        }
    }

    @Test
    fun `summary with nothing accessible skips the signal query`() {
        every { ruleSets.findAllByUserId(1L) } returns emptyList()
        stubSubscribed()
        every { jdbc.queryForObject(match<String> { it.contains("COUNT(*) FROM strategy_subscriptions") }, Int::class.java, 1L) } returns 0

        assertThat(service.summary(1L).todaySignals).isZero()
        verify(exactly = 0) { jdbc.queryForObject(match<String> { it.contains("FROM quant_signals") }, Int::class.java, *anyVararg()) }
    }
}
