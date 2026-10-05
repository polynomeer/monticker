package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.monticker.api.quant.domain.ForwardTestStatus
import com.monticker.api.quant.domain.QuantBacktestResult
import com.monticker.api.quant.domain.QuantEquityPoint
import com.monticker.api.quant.domain.QuantForwardTest
import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import com.monticker.api.quant.infrastructure.QuantForwardTestRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class StrategyPerformanceQueryTest {

    private val backtests = mockk<QuantBacktestResultRepository>()
    private val forwards = mockk<QuantForwardTestRepository>()
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val query = StrategyPerformanceQuery(backtests, forwards, mapper)

    @Test
    fun `downsample keeps first and last points and caps the size`() {
        val values = (0 until 500).map { it.toDouble() }

        val out = StrategyPerformanceQuery.downsample(values, 48)

        assertThat(out).hasSize(48)
        assertThat(out.first()).isEqualTo(0.0)
        assertThat(out.last()).isEqualTo(499.0)
    }

    @Test
    fun `downsample leaves short curves untouched`() {
        assertThat(StrategyPerformanceQuery.downsample(listOf(1.0, 2.0, 3.0), 48)).containsExactly(1.0, 2.0, 3.0)
    }

    @Test
    fun `summarize batches both lookups and prefers a running forward test`() {
        val curve = (0 until 100).map { QuantEquityPoint(LocalDate.of(2025, 1, 1).plusDays(it.toLong()), 1000.0 + it, 0.0) }
        every { backtests.findLatestByRuleSetIds(listOf("a", "b")) } returns listOf(
            QuantBacktestResult(id = 7, ruleSetId = "a", ruleSetVersion = 1, stockId = 2,
                startDate = LocalDate.of(2025, 1, 1), endDate = LocalDate.of(2025, 4, 10),
                initialCapital = BigDecimal(1000), finalCapital = BigDecimal(1099),
                annualReturn = BigDecimal("12.5"), mdd = BigDecimal("3.2"),
                equityCurveJson = mapper.writeValueAsString(curve)),
        )
        val stopped = QuantForwardTest(id = 1, ruleSetId = "a", ruleSetVersion = 1, stockId = 2,
            initialCapital = BigDecimal(1000), cash = BigDecimal(1000), status = ForwardTestStatus.STOPPED,
            startedAt = Instant.parse("2026-05-01T00:00:00Z"))
        val running = QuantForwardTest(id = 2, ruleSetId = "a", ruleSetVersion = 1, stockId = 2,
            initialCapital = BigDecimal(1000), cash = BigDecimal(1000),
            startedAt = Instant.parse("2026-03-01T00:00:00Z"), matchRate = BigDecimal("0.8750"))
        every { forwards.findAllByRuleSetIdIn(listOf("a", "b")) } returns listOf(stopped, running)

        val out = query.summarize(listOf("a", "b", "a"))

        assertThat(out["a"]!!.backtest!!.annualReturn).isEqualTo(12.5)
        assertThat(out["a"]!!.backtest!!.curve).hasSize(StrategyPerformanceQuery.CURVE_POINTS)
        assertThat(out["a"]!!.forward!!.status).isEqualTo("RUNNING")
        assertThat(out["a"]!!.forward!!.matchRate).isEqualTo(0.875)
        assertThat(out["b"]).isEqualTo(StrategyPerformance(null, null))
        verify(exactly = 1) { backtests.findLatestByRuleSetIds(any()) }
    }
}
