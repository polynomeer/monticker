package com.monticker.worker.integration

import com.monticker.worker.alert.AlertEvaluator
import com.monticker.worker.alert.AlertRuleRow
import com.monticker.worker.push.ExpoPushSender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.mail.javamail.JavaMailSender
import java.math.BigDecimal

/**
 * AlertEvaluator 통합 계약 검증 (mock 기반, 단위 수준).
 *
 * TODO: build.gradle.kts에 추가된 `integrationTest` 소스셋으로 옮기고 실제
 * Postgres/Redis Testcontainers를 사용하는 진짜 통합 테스트로 교체한다.
 * (test-engineer.md 참고: "Never mock the database in integration tests.")
 */
class AlertEvaluatorIntegrationTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val pushSender = mockk<ExpoPushSender>(relaxed = true)
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val evaluator = AlertEvaluator(jdbc, pushSender, esOps, redis, mailSender)

    @BeforeEach
    fun setup() {
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), any()) } returns emptyList()
    }

    @Test
    fun `PRICE_ABOVE 임계값 초과 시 alert_histories에 기록된다`() {
        val rule = AlertRuleRow(
            id            = 1L,
            userId        = 1L,
            stockId       = 1L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 75000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 1L) } returns listOf(rule)
        every { jdbc.queryForObject(any<String>(), Int::class.java, rule.id) } returns 0
        every { redis.opsForValue().setIfAbsent(any(), any(), any<java.time.Duration>()) } returns true
        every {
            jdbc.queryForObject(any<String>(), Long::class.java, *anyVararg())
        } returns 1L
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns emptyList()

        evaluator.processAlert(stockId = 1L, price = BigDecimal("75500"))

        verify(atLeast = 1) { jdbc.queryForObject(any<String>(), Long::class.java, *anyVararg()) }
    }

    @Test
    fun `10분 이내 중복 alert는 histories에 추가하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 1L,
            userId        = 1L,
            stockId       = 1L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 75000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 1L) } returns listOf(rule)
        every { jdbc.queryForObject(any<String>(), Int::class.java, rule.id) } returns 1

        evaluator.processAlert(stockId = 1L, price = BigDecimal("75500"))

        // histories INSERT(RETURNING)가 호출되지 않아야 한다
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Long::class.java, *anyVararg()) }
    }

    @Test
    fun `PRICE_BELOW 조건 미충족이면 alert를 발생시키지 않는다`() {
        val rule = AlertRuleRow(
            id            = 2L,
            userId        = 1L,
            stockId       = 1L,
            ruleType      = "PRICE_BELOW",
            conditionJson = """{"threshold": 60000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 1L) } returns listOf(rule)

        evaluator.processAlert(stockId = 1L, price = BigDecimal("70500"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Int::class.java, any()) }
    }
}
