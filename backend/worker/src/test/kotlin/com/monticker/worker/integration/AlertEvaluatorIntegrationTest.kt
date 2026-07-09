package com.monticker.worker.integration

import com.monticker.worker.alert.AlertEvaluator
import com.monticker.worker.alert.AlertRuleRow
import com.monticker.worker.push.ExpoPushSender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

/**
 * AlertEvaluator 통합 계약 검증.
 *
 * 실제 PostgreSQL(Testcontainers)은 build.gradle.kts에 testcontainers 의존성이 없어
 * 단위 수준에서 전체 흐름을 검증한다. 실제 DB 통합은 별도 @IntegrationTest 슈트로 분리한다.
 */
class AlertEvaluatorIntegrationTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val pushSender = mockk<ExpoPushSender>(relaxed = true)
    private val evaluator = AlertEvaluator(jdbc, pushSender)

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
