package com.monticker.api.alert.application

import com.monticker.api.common.redis.RedisGuard
import org.springframework.data.redis.core.StringRedisTemplate
import com.monticker.api.common.metrics.SearchMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertHistorySearchRepository
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

class AlertServiceTest {

    private val repo = mockk<AlertRuleRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val alertHistorySearchRepository = mockk<AlertHistorySearchRepository>(relaxed = true)
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val service = AlertService(repo, ObjectMapper(), jdbc, alertHistorySearchRepository, esOps, SearchMetrics(SimpleMeterRegistry()), redis, RedisGuard(SimpleMeterRegistry()))

    @Test
    fun `createRule saves and returns rule`() {
        val rule = AlertRule(
            id = 1L, userId = 1L, stockId = 1L,
            ruleType = AlertRuleType.PRICE_ABOVE,
            conditionJson = """{"threshold":75000}""",
        )
        every { repo.save(any()) } returns rule

        val result = service.createRule(1L, 1L, AlertRuleType.PRICE_ABOVE, mapOf("threshold" to 75000))

        assertThat(result.ruleType).isEqualTo(AlertRuleType.PRICE_ABOVE)
        verify { repo.save(any()) }
    }

    @Test
    fun `deactivateRule throws when rule not found`() {
        every { repo.findById(99L) } returns Optional.empty()

        assertThatThrownBy { service.deactivateRule(1L, 99L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `deactivateRule throws on ownership mismatch`() {
        val rule = AlertRule(
            id = 1L, userId = 2L, stockId = null,
            ruleType = AlertRuleType.VOLUME_SURGE,
            conditionJson = "{}",
        )
        every { repo.findById(1L) } returns Optional.of(rule)

        assertThatThrownBy { service.deactivateRule(1L, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── ADR-073 켜기/끄기 ────────────────────────────────────────────────

    private fun rule(userId: Long = 1L, active: Boolean = true, deleted: Boolean = false, type: AlertRuleType = AlertRuleType.PRICE_ABOVE, cond: String = """{"threshold":1000}""") =
        AlertRule(id = 1L, userId = userId, stockId = 5L, ruleType = type, conditionJson = cond, isActive = active,
            deletedAt = if (deleted) java.time.Instant.now() else null)

    @Test
    fun `setActive turns a paused rule back on with a conditional update`() {
        every { repo.findById(1L) } returnsMany listOf(Optional.of(rule(active = false)), Optional.of(rule(active = true)))
        every { repo.updateActive(1L, 1L, true) } returns 1

        val result = service.setActive(1L, 1L, true)

        assertThat(result.isActive).isTrue()
        verify { repo.updateActive(1L, 1L, true) }
        verify { redis.convertAndSend(AlertService.ALERT_RULES_CHANGED_CHANNEL, "5") }
    }

    @Test
    fun `setActive hides deleted and foreign rules as not found`() {
        every { repo.findById(1L) } returns Optional.of(rule(deleted = true, active = false))
        assertThatThrownBy { service.setActive(1L, 1L, true) }.isInstanceOf(NoSuchElementException::class.java)

        every { repo.findById(1L) } returns Optional.of(rule(userId = 2L))
        assertThatThrownBy { service.setActive(1L, 1L, false) }.isInstanceOf(NoSuchElementException::class.java)
        verify(exactly = 0) { repo.updateActive(any(), any(), any()) }
    }

    @Test
    fun `setActive does not resurrect a rule deleted concurrently`() {
        every { repo.findById(1L) } returns Optional.of(rule(active = false))
        every { repo.updateActive(1L, 1L, true) } returns 0   // 읽은 뒤 다른 요청이 삭제

        assertThatThrownBy { service.setActive(1L, 1L, true) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `rules nothing evaluates cannot be turned back on`() {
        every { repo.findById(1L) } returns Optional.of(rule(active = false, type = AlertRuleType.NEWS_PUBLISHED, cond = "{}"))

        assertThatThrownBy { service.setActive(1L, 1L, true) }
            .isInstanceOf(com.monticker.api.common.exception.BusinessRuleException::class.java)
        verify(exactly = 0) { repo.updateActive(any(), any(), any()) }
    }

    @Test
    fun `setActive to the current state is a no-op`() {
        every { repo.findById(1L) } returns Optional.of(rule(active = true))

        service.setActive(1L, 1L, true)

        verify(exactly = 0) { repo.updateActive(any(), any(), any()) }
    }

    @Test
    fun `DELETE marks the rule deleted rather than paused`() {
        every { repo.findById(1L) } returns Optional.of(rule())
        every { repo.markDeleted(1L, 1L) } returns 1

        service.deactivateRule(1L, 1L)

        verify { repo.markDeleted(1L, 1L) }
    }

    // ── ADR-073 읽음 ────────────────────────────────────────────────────

    @Test
    fun `markRead of someone else's history is not found`() {
        every { jdbc.update(match<String> { it.contains("UPDATE alert_histories") }, 9L, 1L) } returns 0
        every { jdbc.query(match<String> { it.contains("SELECT ah.id, ah.read_at") }, any<org.springframework.jdbc.core.RowMapper<Pair<Long, java.time.Instant?>>>(), *anyVararg()) } returns emptyList()

        assertThatThrownBy { service.markRead(1L, 9L) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `markRead of an already read history is idempotent`() {
        every { jdbc.update(match<String> { it.contains("UPDATE alert_histories") }, 9L, 1L) } returns 0
        every { jdbc.query(match<String> { it.contains("SELECT ah.id, ah.read_at") }, any<org.springframework.jdbc.core.RowMapper<Pair<Long, java.time.Instant?>>>(), *anyVararg()) } returns
            listOf(9L to java.time.Instant.now())

        service.markRead(1L, 9L)
    }
}
