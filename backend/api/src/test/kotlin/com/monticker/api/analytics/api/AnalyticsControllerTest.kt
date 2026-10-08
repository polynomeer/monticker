package com.monticker.api.analytics.api

import com.monticker.api.analytics.application.AnalysisPeriod
import com.monticker.api.analytics.application.FrontierAnalysis
import com.monticker.api.analytics.application.HoldingsComparisonService
import com.monticker.api.analytics.application.MaxSharpePoint
import com.monticker.api.analytics.application.OptimizationResult
import com.monticker.api.analytics.application.PeriodInfo
import com.monticker.api.analytics.application.PortfolioOptimizerService
import com.monticker.api.analytics.application.PositionSizerService
import com.monticker.api.analytics.application.SamplePoint
import com.monticker.api.analytics.application.TaxOptimizerService
import com.monticker.api.common.exception.GlobalExceptionHandler
import com.monticker.api.common.time.KstPeriod
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate

class AnalyticsControllerTest {

    private val optimizer = mockk<PortfolioOptimizerService>()
    private val holdings = mockk<HoldingsComparisonService>()
    private val controller = AnalyticsController(optimizer, mockk<TaxOptimizerService>(), mockk<PositionSizerService>(), holdings)

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val analysis = FrontierAnalysis(
        stockIds = listOf(2L, 3L),
        period = PeriodInfo("6M", LocalDate.of(2026, 4, 8), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 4, 9), LocalDate.of(2026, 10, 7), 120),
        frontier = emptyList(),
        samples = listOf(SamplePoint(0.1, 0.2, 0.5)),
        maxSharpe = MaxSharpePoint(mapOf(2L to 0.4, 3L to 0.6), 0.12, 0.18, 0.66),
        riskFreeRate = 0.0,
        seed = 1L,
    )

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(1L, null, emptyList())
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `frontier passes the resolved preset period and sample count and returns samples and max-Sharpe`() {
        val period = slot<AnalysisPeriod>()
        every { optimizer.getEfficientFrontier(1L, listOf(2L, 3L), capture(period), 500) } returns analysis

        mockMvc.perform(get("/api/analytics/portfolio/frontier?stockIds=2&stockIds=3&period=6M&samples=500"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.samples[0].sharpe").value(0.5))
            .andExpect(jsonPath("$.maxSharpe.sharpe").value(0.66))
            .andExpect(jsonPath("$.period.observations").value(120))
            .andExpect(jsonPath("$.riskFreeRate").value(0.0))

        assertThat(period.captured.label).isEqualTo("6M")
        assertThat(period.captured.to).isEqualTo(KstPeriod.today())
    }

    @Test
    fun `frontier accepts a custom KST date range`() {
        val period = slot<AnalysisPeriod>()
        every { optimizer.getEfficientFrontier(1L, any(), capture(period), any()) } returns analysis

        mockMvc.perform(get("/api/analytics/portfolio/frontier?stockIds=2&stockIds=3&period=CUSTOM&from=2025-01-02&to=2025-12-30"))
            .andExpect(status().isOk)

        assertThat(period.captured.from).isEqualTo(LocalDate.of(2025, 1, 2))
        assertThat(period.captured.to).isEqualTo(LocalDate.of(2025, 12, 30))
    }

    @Test
    fun `frontier rejects an out-of-bounds custom range with 400`() {
        mockMvc.perform(get("/api/analytics/portfolio/frontier?stockIds=2&stockIds=3&period=CUSTOM&from=2020-01-01&to=2025-01-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("분석 기간은 최대 3년입니다"))
        verify(exactly = 0) { optimizer.getEfficientFrontier(any(), any(), any(), any()) }
    }

    @Test
    fun `frontier rejects an unknown preset and a malformed date with 400`() {
        mockMvc.perform(get("/api/analytics/portfolio/frontier?stockIds=2&stockIds=3&period=5Y"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/analytics/portfolio/frontier?stockIds=2&stockIds=3&period=CUSTOM&from=2025-13-01&to=2025-12-01"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `insufficient overlapping history surfaces as 400 with the service message`() {
        every { optimizer.getEfficientFrontier(1L, any(), any(), any()) } throws
            IllegalArgumentException("데이터 부족: 분석 기간(2026-07-08~2026-10-08)에 모든 종목이 함께 거래된 수익률이 12일입니다.")

        mockMvc.perform(get("/api/analytics/portfolio/frontier?stockIds=2&stockIds=3&period=3M"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("데이터 부족: 분석 기간(2026-07-08~2026-10-08)에 모든 종목이 함께 거래된 수익률이 12일입니다."))
    }

    @Test
    fun `optimize uses the same period for the holdings comparison`() {
        val optPeriod = slot<AnalysisPeriod>()
        val cmpPeriod = slot<AnalysisPeriod>()
        every { optimizer.optimize(1L, listOf(2L, 3L), null, capture(optPeriod)) } returns
            OptimizationResult(stockIds = listOf(2L, 3L), weights = mapOf(2L to 0.5, 3L to 0.5))
        every { holdings.compare(1L, listOf(2L, 3L), capture(cmpPeriod)) } returns null

        mockMvc.perform(get("/api/analytics/portfolio/optimize?stockIds=2&stockIds=3&compareHoldings=true&period=2Y"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.weights.2").value(0.5))

        assertThat(optPeriod.captured.label).isEqualTo("2Y")
        assertThat(cmpPeriod.captured).isEqualTo(optPeriod.captured)
    }

    @Test
    fun `optimize defaults to one year`() {
        val optPeriod = slot<AnalysisPeriod>()
        every { optimizer.optimize(1L, any(), null, capture(optPeriod)) } returns OptimizationResult(stockIds = listOf(2L, 3L))

        mockMvc.perform(get("/api/analytics/portfolio/optimize?stockIds=2&stockIds=3"))
            .andExpect(status().isOk)

        assertThat(optPeriod.captured.label).isEqualTo("1Y")
    }
}
