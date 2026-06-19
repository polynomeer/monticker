package com.monticker.worker.alert

import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

class AlertEvaluatorTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val evaluator = AlertEvaluator(jdbc)

    @Test
    fun `PRICE_ABOVE triggers when current price exceeds threshold`() {
        val rule = AlertRuleRow(
            id = 1L, userId = 1L, stockId = 1L,
            ruleType = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns listOf(rule)
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq(1L))
        } returns BigDecimal("75000")
        every {
            jdbc.queryForObject(match<String> { it.contains("alert_histories") }, eq(Int::class.java), eq(1L))
        } returns 0

        evaluator.evaluate()

        verify { jdbc.update(match<String> { it.contains("INSERT INTO alert_histories") }, any(), any(), any(), any()) }
    }

    @Test
    fun `does not fire duplicate alert within cooldown window`() {
        val rule = AlertRuleRow(
            id = 1L, userId = 1L, stockId = 1L,
            ruleType = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns listOf(rule)
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq(1L))
        } returns BigDecimal("75000")
        every {
            jdbc.queryForObject(match<String> { it.contains("alert_histories") }, eq(Int::class.java), eq(1L))
        } returns 1  // already fired within cooldown

        evaluator.evaluate()

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT INTO alert_histories") }, *anyVararg()) }
    }

    @Test
    fun `PRICE_BELOW does not trigger when price is above threshold`() {
        val rule = AlertRuleRow(
            id = 2L, userId = 1L, stockId = 1L,
            ruleType = "PRICE_BELOW",
            conditionJson = """{"threshold": 60000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>()) } returns listOf(rule)
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq(1L))
        } returns BigDecimal("65000")

        evaluator.evaluate()

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT INTO alert_histories") }, *anyVararg()) }
    }
}
