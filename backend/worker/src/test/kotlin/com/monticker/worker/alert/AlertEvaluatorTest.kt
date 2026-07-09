package com.monticker.worker.alert

import com.monticker.worker.push.ExpoPushSender
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

class AlertEvaluatorTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val pushSender = mockk<ExpoPushSender>(relaxed = true)
    private val evaluator = AlertEvaluator(jdbc, pushSender)

    @BeforeEach
    fun setup() {
        // 기본: 해당 stockId에 활성 규칙 없음
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), any()) } returns emptyList()
    }

    @Test
    fun `활성 규칙 없으면 push를 보내지 않는다`() {
        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `PRICE_ABOVE 조건 충족 + 디바이스 토큰 있으면 push를 보낸다`() {
        val rule = AlertRuleRow(
            id            = 1L,
            userId        = 10L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, rule.id) } returns 0
        every {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        } returns 42L
        every {
            jdbc.queryForList(any<String>(), String::class.java, rule.userId)
        } returns listOf("ExponentPushToken[abc123]")
        every { jdbc.update(any<String>(), any(), 42L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[abc123]" }) }
        verify { jdbc.update(match<String> { it.contains("delivery_status") }, any(), 42L) }
    }

    @Test
    fun `디바이스 토큰 없으면 push를 보내지 않는다`() {
        val rule = AlertRuleRow(
            id            = 2L,
            userId        = 20L,
            stockId       = 5L,
            ruleType      = "PRICE_BELOW",
            conditionJson = """{"threshold": 60000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, rule.id) } returns 0
        every {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        } returns 99L
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns emptyList()

        evaluator.processAlert(stockId = 5L, price = BigDecimal("55000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `10분 이내 이미 발송된 경우 중복 발송하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 3L,
            userId        = 30L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, rule.id) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `PRICE_ABOVE 조건 미충족이면 아무 것도 하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 4L,
            userId        = 40L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 80000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)

        // 현재가(75000) < 임계값(80000) → 발동 안 됨
        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) { jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, any()) }
    }
}
