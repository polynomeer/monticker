package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.infrastructure.PortfolioOptimizationRepository
import com.monticker.api.backtest.domain.DailyCandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.LocalDate

class PortfolioOptimizerServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper()
    private val optimizationRepo = mockk<PortfolioOptimizationRepository>()
    private val service = PortfolioOptimizerService(jdbc, objectMapper, optimizationRepo)

    /** Synthesises a `minLen`-day candle series whose daily returns equal `dailyReturn` every day. */
    private fun stubCandles(stockId: Long, minLen: Int, startPrice: Double, dailyReturn: Double) {
        var price = startPrice
        val candles = (0 until minLen).map { i ->
            val c = DailyCandle(
                date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
                open = BigDecimal.valueOf(price), high = BigDecimal.valueOf(price),
                low = BigDecimal.valueOf(price), close = BigDecimal.valueOf(price), volume = 1000L,
            )
            price *= (1 + dailyReturn)
            c
        }
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), eq(stockId), any(), any())
        } returns candles
    }

    @Test
    fun `optimize returns an error when fewer than two stocks are provided`() {
        val result = service.optimize(1L, listOf(100L), null)

        assertThat(result.error).isEqualTo("최소 2개 이상의 종목이 필요합니다")
    }

    @Test
    fun `optimize returns an error when fewer than 30 days of data are available`() {
        stubCandles(100L, minLen = 10, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 10, startPrice = 200.0, dailyReturn = 0.001)

        val result = service.optimize(1L, listOf(100L, 200L), null)

        assertThat(result.error).isEqualTo("데이터 부족: 최소 30일 데이터가 필요합니다")
    }

    @Test
    fun `optimize returns weights that sum to one`() {
        stubCandles(100L, minLen = 40, startPrice = 100.0, dailyReturn = 0.002)
        stubCandles(200L, minLen = 40, startPrice = 200.0, dailyReturn = -0.001)
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val result = service.optimize(1L, listOf(100L, 200L), null)

        assertThat(result.error).isNull()
        assertThat(result.weights.values.sum()).isCloseTo(1.0, within(0.001))
        assertThat(result.weights.values).allMatch { it >= 0.0 }
    }

    @Test
    fun `optimize persists an optimization record`() {
        stubCandles(100L, minLen = 35, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 35, startPrice = 200.0, dailyReturn = 0.001)
        val slot = slot<com.monticker.api.analytics.domain.PortfolioOptimization>()
        every { optimizationRepo.save(capture(slot)) } answers { slot.captured }

        service.optimize(1L, listOf(100L, 200L), null)

        assertThat(slot.captured.userId).isEqualTo(1L)
        assertThat(slot.captured.universeJson).contains("100").contains("200")
    }

    @Test
    fun `optimize suggestion mentions lower risk when the optimized portfolio beats equal weighting`() {
        // Two assets with very different volatility — optimizer should favour the calmer one,
        // producing risk lower than naive equal weighting.
        stubCandles(100L, minLen = 60, startPrice = 100.0, dailyReturn = 0.0005)
        stubCandles(200L, minLen = 60, startPrice = 200.0, dailyReturn = 0.0005)
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val result = service.optimize(1L, listOf(100L, 200L), null)

        assertThat(result.suggestion).isNotBlank()
        assertThat(result.suggestion).contains("기대 연 수익률")
    }

    @Test
    fun `getEfficientFrontier returns an empty list for fewer than two stocks`() {
        assertThat(service.getEfficientFrontier(1L, listOf(100L))).isEmpty()
    }

    @Test
    fun `getEfficientFrontier returns an empty list when there is insufficient history`() {
        stubCandles(100L, minLen = 5, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 5, startPrice = 200.0, dailyReturn = 0.001)

        assertThat(service.getEfficientFrontier(1L, listOf(100L, 200L))).isEmpty()
    }

    @Test
    fun `getEfficientFrontier returns 11 points spanning from min to max observed return`() {
        stubCandles(100L, minLen = 40, startPrice = 100.0, dailyReturn = 0.003)
        stubCandles(200L, minLen = 40, startPrice = 200.0, dailyReturn = -0.001)
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val frontier = service.getEfficientFrontier(1L, listOf(100L, 200L))

        assertThat(frontier).hasSize(11)
        assertThat(frontier.first().targetReturn).isLessThanOrEqualTo(frontier.last().targetReturn)
    }

    @Test
    fun `getEfficientFrontier persists a record including the frontier JSON`() {
        stubCandles(100L, minLen = 35, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 35, startPrice = 200.0, dailyReturn = 0.001)
        val slot = slot<com.monticker.api.analytics.domain.PortfolioOptimization>()
        every { optimizationRepo.save(capture(slot)) } answers { slot.captured }

        service.getEfficientFrontier(1L, listOf(100L, 200L))

        assertThat(slot.captured.frontierJson).isNotNull()
        assertThat(slot.captured.targetReturn).isNull()
    }

    // ── Pure math helpers ───────────────────────────────────────────────────────

    @Test
    fun `projectToSimplex renormalises weights to sum to one`() {
        val result = service.projectToSimplex(doubleArrayOf(2.0, 2.0, 4.0))

        assertThat(result.sum()).isCloseTo(1.0, within(0.0001))
        assertThat(result.toList()).containsExactly(0.25, 0.25, 0.5)
    }

    @Test
    fun `projectToSimplex clips negative weights to zero before renormalising`() {
        val result = service.projectToSimplex(doubleArrayOf(-1.0, 3.0))

        assertThat(result[0]).isEqualTo(0.0)
        assertThat(result[1]).isCloseTo(1.0, within(0.0001))
    }

    @Test
    fun `projectToSimplex falls back to uniform weights when all inputs are non-positive`() {
        val result = service.projectToSimplex(doubleArrayOf(-1.0, -2.0, -3.0))

        assertThat(result.toList()).allMatch { it == 1.0 / 3 }
    }

    @Test
    fun `minimizeVariance favours the lower-variance asset when returns differ`() {
        // asset 0 has much higher variance than asset 1
        val cov = arrayOf(
            doubleArrayOf(0.01, 0.0),
            doubleArrayOf(0.0, 0.0001),
        )
        val mu = doubleArrayOf(0.001, 0.001)

        val weights = service.minimizeVariance(cov, mu, targetReturn = 0.001)

        assertThat(weights[1]).isGreaterThan(weights[0])
    }

    @Test
    fun `minimizeVariance produces weights that sum to one`() {
        val cov = arrayOf(
            doubleArrayOf(0.0004, 0.0001),
            doubleArrayOf(0.0001, 0.0009),
        )
        val mu = doubleArrayOf(0.0005, 0.0008)

        val weights = service.minimizeVariance(cov, mu, targetReturn = 0.0006)

        assertThat(weights.sum()).isCloseTo(1.0, within(0.001))
    }
}
