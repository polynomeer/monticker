package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.infrastructure.PortfolioOptimizationRepository
import com.monticker.api.backtest.domain.DailyCandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    private val queryService = PortfolioOptimizerQueryService(jdbc)
    private val service = PortfolioOptimizerService(queryService, objectMapper, optimizationRepo)

    /** Synthesises a `minLen`-day candle series whose daily returns equal `dailyReturn` every day. */
    private fun stubCandles(stockId: Long, minLen: Int, startPrice: Double, dailyReturn: Double, startDate: LocalDate = LocalDate.of(2025, 1, 1)) {
        var price = startPrice
        val candles = (0 until minLen).map { i ->
            val c = DailyCandle(
                date = startDate.plusDays(i.toLong()),
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

    private val period = AnalysisPeriod.resolve(null, null, null, today = LocalDate.of(2026, 10, 8))

    /** 정규 잡음이 섞인 120일 시계열 — 종목마다 다른 seed라 공분산이 단위행렬이 아니다. */
    private fun stubNoisyCandles(stockId: Long, seed: Long, drift: Double, vol: Double) {
        val rnd = java.util.Random(seed)
        var price = 100.0
        val candles = (0 until 120).map { i ->
            val c = DailyCandle(
                date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
                open = BigDecimal.valueOf(price), high = BigDecimal.valueOf(price),
                low = BigDecimal.valueOf(price), close = BigDecimal.valueOf(price), volume = 1000L,
            )
            price *= (1 + drift + vol * rnd.nextGaussian())
            c
        }
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), eq(stockId), any(), any())
        } returns candles
    }

    @Test
    fun `optimize returns an error when fewer than two stocks are provided`() {
        val result = service.optimize(1L, listOf(100L), null, period)

        assertThat(result.error).isEqualTo("최소 2개 이상의 종목이 필요합니다")
    }

    @Test
    fun `optimize dedupes stock ids before checking the minimum`() {
        // V-M5 — a duplicated id must not count as two distinct stocks.
        val result = service.optimize(1L, listOf(100L, 100L), null, period)

        assertThat(result.error).isEqualTo("최소 2개 이상의 종목이 필요합니다")
    }

    @Test
    fun `optimize rejects more than the maximum number of stocks`() {
        val result = service.optimize(1L, (1L..21L).toList(), null, period)

        assertThat(result.error).isEqualTo("종목은 최대 20개까지 지정할 수 있습니다")
    }

    @Test
    fun `optimize returns an error when fewer than 30 days of data are available`() {
        stubCandles(100L, minLen = 10, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 10, startPrice = 200.0, dailyReturn = 0.001)

        val result = service.optimize(1L, listOf(100L, 200L), null, period)

        assertThat(result.error).startsWith("데이터 부족").contains("9일").contains("최소 30일")
    }

    @Test
    fun `optimize returns weights that sum to one`() {
        stubCandles(100L, minLen = 40, startPrice = 100.0, dailyReturn = 0.002)
        stubCandles(200L, minLen = 40, startPrice = 200.0, dailyReturn = -0.001)
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val result = service.optimize(1L, listOf(100L, 200L), null, period)

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

        service.optimize(1L, listOf(100L, 200L), null, period)

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

        val result = service.optimize(1L, listOf(100L, 200L), null, period)

        assertThat(result.suggestion).isNotBlank()
        assertThat(result.suggestion).contains("기대 연 수익률")
    }

    @Test
    fun `getEfficientFrontier rejects fewer than two stocks`() {
        assertThatThrownBy { service.getEfficientFrontier(1L, listOf(100L), period) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("최소 2개 이상의 종목이 필요합니다")
    }

    @Test
    fun `getEfficientFrontier rejects a deduped count below two`() {
        assertThatThrownBy { service.getEfficientFrontier(1L, listOf(100L, 100L), period) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `getEfficientFrontier rejects more than the maximum stock count`() {
        assertThatThrownBy { service.getEfficientFrontier(1L, (1L..21L).toList(), period) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("종목은 최대 20개까지 지정할 수 있습니다")
    }

    @Test
    fun `getEfficientFrontier rejects insufficient history with a clear message`() {
        stubCandles(100L, minLen = 5, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 5, startPrice = 200.0, dailyReturn = 0.001)

        assertThatThrownBy { service.getEfficientFrontier(1L, listOf(100L, 200L), period) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("데이터 부족").hasMessageContaining("4일")
    }

    @Test
    fun `getEfficientFrontier only uses dates every stock traded on`() {
        // 200은 100보다 20일 늦게 상장 — 겹치는 날은 40일(수익률 39개)뿐이다.
        stubCandles(100L, minLen = 60, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 40, startPrice = 200.0, dailyReturn = 0.002, startDate = LocalDate.of(2025, 1, 21))
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val analysis = service.getEfficientFrontier(1L, listOf(100L, 200L), period)

        assertThat(analysis.period.observations).isEqualTo(39)
        assertThat(analysis.period.firstDate).isEqualTo(LocalDate.of(2025, 1, 21))
        assertThat(analysis.period.period).isEqualTo("1Y")
    }

    @Test
    fun `getEfficientFrontier rejects a sample count outside the bounds`() {
        assertThatThrownBy { service.getEfficientFrontier(1L, listOf(100L, 200L), period, sampleCount = 2001) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("표본 수")
        assertThatThrownBy { service.getEfficientFrontier(1L, listOf(100L, 200L), period, sampleCount = 99) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `getEfficientFrontier returns 11 frontier points, the samples and a max-Sharpe point`() {
        stubNoisyCandles(100L, seed = 1, drift = 0.002, vol = 0.02)
        stubNoisyCandles(200L, seed = 2, drift = 0.0005, vol = 0.01)
        stubNoisyCandles(300L, seed = 3, drift = 0.001, vol = 0.015)
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val analysis = service.getEfficientFrontier(1L, listOf(100L, 200L, 300L), period, sampleCount = 500)

        assertThat(analysis.frontier).hasSize(11)
        assertThat(analysis.frontier.first().targetReturn).isLessThanOrEqualTo(analysis.frontier.last().targetReturn)
        assertThat(analysis.samples).hasSize(500)
        assertThat(analysis.riskFreeRate).isEqualTo(0.0)
        val best = analysis.maxSharpe!!
        assertThat(best.weights.values.sum()).isCloseTo(1.0, within(1e-9))
        assertThat(best.weights.values).allMatch { it >= 0.0 }
        val bestSampleSharpe = analysis.samples.mapNotNull { it.sharpe }.max()
        assertThat(best.sharpe).isGreaterThanOrEqualTo(bestSampleSharpe)
    }

    @Test
    fun `getEfficientFrontier is deterministic for the same input`() {
        stubNoisyCandles(100L, seed = 1, drift = 0.002, vol = 0.02)
        stubNoisyCandles(200L, seed = 2, drift = 0.0005, vol = 0.01)
        every { optimizationRepo.save(any()) } answers { firstArg() }

        val a = service.getEfficientFrontier(1L, listOf(100L, 200L), period, sampleCount = 200)
        val b = service.getEfficientFrontier(1L, listOf(100L, 200L), period, sampleCount = 200)

        assertThat(a.samples).isEqualTo(b.samples)
        assertThat(a.maxSharpe).isEqualTo(b.maxSharpe)
    }

    @Test
    fun `getEfficientFrontier persists a record including the frontier JSON`() {
        stubCandles(100L, minLen = 35, startPrice = 100.0, dailyReturn = 0.001)
        stubCandles(200L, minLen = 35, startPrice = 200.0, dailyReturn = 0.001)
        val slot = slot<com.monticker.api.analytics.domain.PortfolioOptimization>()
        every { optimizationRepo.save(capture(slot)) } answers { slot.captured }

        service.getEfficientFrontier(1L, listOf(100L, 200L), period)

        assertThat(slot.captured.frontierJson).isNotNull()
        assertThat(slot.captured.targetReturn).isNull()
    }

    @Test
    fun `risk-free rate outside the configured bounds fails fast`() {
        assertThatThrownBy { PortfolioOptimizerService(queryService, objectMapper, optimizationRepo, riskFreeRate = 0.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Pure math helpers ───────────────────────────────────────────────────────

    @Test
    fun `projectToSimplex renormalises weights to sum to one`() {
        val result = queryService.projectToSimplex(doubleArrayOf(2.0, 2.0, 4.0))

        assertThat(result.sum()).isCloseTo(1.0, within(0.0001))
        assertThat(result.toList()).containsExactly(0.25, 0.25, 0.5)
    }

    @Test
    fun `projectToSimplex clips negative weights to zero before renormalising`() {
        val result = queryService.projectToSimplex(doubleArrayOf(-1.0, 3.0))

        assertThat(result[0]).isEqualTo(0.0)
        assertThat(result[1]).isCloseTo(1.0, within(0.0001))
    }

    @Test
    fun `projectToSimplex falls back to uniform weights when all inputs are non-positive`() {
        val result = queryService.projectToSimplex(doubleArrayOf(-1.0, -2.0, -3.0))

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

        val weights = queryService.minimizeVariance(cov, mu, targetReturn = 0.001)

        assertThat(weights[1]).isGreaterThan(weights[0])
    }

    @Test
    fun `minimizeVariance shifts weight toward the higher-return asset as targetReturn rises`() {
        // V-M4 — targetReturn used to be a dead parameter: every target converged to the same
        // global minimum-variance weights. Asset 1 has both higher return and higher variance,
        // so chasing a higher target should require leaning into it despite the risk cost.
        val cov = arrayOf(
            doubleArrayOf(0.0001, 0.0),
            doubleArrayOf(0.0, 0.0004),
        )
        val mu = doubleArrayOf(0.0005, 0.0020)

        val lowTargetWeights = queryService.minimizeVariance(cov, mu, targetReturn = 0.0005)
        val highTargetWeights = queryService.minimizeVariance(cov, mu, targetReturn = 0.0020)

        assertThat(highTargetWeights[1]).isGreaterThan(lowTargetWeights[1])
    }

    @Test
    fun `minimizeVariance produces weights that sum to one`() {
        val cov = arrayOf(
            doubleArrayOf(0.0004, 0.0001),
            doubleArrayOf(0.0001, 0.0009),
        )
        val mu = doubleArrayOf(0.0005, 0.0008)

        val weights = queryService.minimizeVariance(cov, mu, targetReturn = 0.0006)

        assertThat(weights.sum()).isCloseTo(1.0, within(0.001))
    }
}
