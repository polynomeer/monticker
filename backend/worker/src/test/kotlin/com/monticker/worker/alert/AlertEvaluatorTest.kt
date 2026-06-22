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
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns emptyList()
    }

    @Test
    fun `evaluate does nothing when no active rules`() {
        evaluator.evaluate()

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `dispatchAlert sends push when device tokens exist`() {
        val rule = AlertRuleRow(
            id = 1L,
            userId = 10L,
            stockId = 5L,
            ruleType = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns listOf(rule)
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, any())
        } returns java.math.BigDecimal("75000")
        every {
            jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, any())
        } returns 0
        every {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        } returns 42L
        every {
            jdbc.queryForList(any<String>(), String::class.java, any())
        } returns listOf("ExponentPushToken[abc123]")
        every { jdbc.update(any<String>(), any(), any()) } returns 1

        evaluator.evaluate()

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[abc123]" }) }
        verify { jdbc.update(match<String> { it.contains("delivery_status") }, any(), 42L) }
    }

    @Test
    fun `dispatchAlert skips push when no device tokens`() {
        val rule = AlertRuleRow(
            id = 2L,
            userId = 20L,
            stockId = 5L,
            ruleType = "PRICE_BELOW",
            conditionJson = """{"threshold": 60000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns listOf(rule)
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, any())
        } returns java.math.BigDecimal("55000")
        every {
            jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, any())
        } returns 0
        every {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        } returns 99L
        every {
            jdbc.queryForList(any<String>(), String::class.java, any())
        } returns emptyList()

        evaluator.evaluate()

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `dispatchAlert skips when already fired within 10 minutes`() {
        val rule = AlertRuleRow(
            id = 3L,
            userId = 30L,
            stockId = 5L,
            ruleType = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns listOf(rule)
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, any())
        } returns java.math.BigDecimal("75000")
        every {
            jdbc.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, any())
        } returns 1

        evaluator.evaluate()

        verify(exactly = 0) { pushSender.send(any()) }
    }
}
