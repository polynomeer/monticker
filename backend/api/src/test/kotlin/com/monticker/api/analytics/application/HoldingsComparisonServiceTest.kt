package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.monticker.api.analytics.api.OptimizeResponse
import com.monticker.api.paper.application.HoldingResponse
import com.monticker.api.paper.application.PaperPortfolioQueryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HoldingsComparisonServiceTest {

    private val paper = mockk<PaperPortfolioQueryService>()
    private val optimizer = mockk<PortfolioOptimizerQueryService>()
    private val service = HoldingsComparisonService(paper, optimizer)

    private fun holding(id: Long, value: Long) = HoldingResponse(
        stockId = id, symbol = "S$id", name = "N$id", quantity = 1, avgPrice = BigDecimal(value),
        currentPrice = BigDecimal(value), value = BigDecimal(value), pnl = BigDecimal.ZERO, pnlRate = 0.0,
    )

    @Test
    fun `weights come from market value of held stocks within the analysed set`() {
        every { paper.buildHoldings(1L) } returns listOf(holding(2, 300), holding(3, 100), holding(9, 400))
        every { optimizer.evaluateWeights(listOf(2L, 3L, 5L), any()) } returns (0.1 to 0.2)

        val point = service.compare(1L, listOf(2L, 3L, 5L))!!

        assertThat(point.weights[2L]).isCloseTo(0.75, within(1e-9))
        assertThat(point.weights[3L]).isCloseTo(0.25, within(1e-9))
        assertThat(point.coveredValueRatio).isCloseTo(0.5, within(1e-9))
        assertThat(point.notHeld).containsExactly(5L)
        assertThat(point.expectedReturn).isEqualTo(0.1)
        verify { optimizer.evaluateWeights(listOf(2L, 3L, 5L), mapOf(2L to 0.75, 3L to 0.25)) }
    }

    @Test
    fun `no comparison point when none of the analysed stocks is held`() {
        every { paper.buildHoldings(1L) } returns listOf(holding(9, 400))

        assertThat(service.compare(1L, listOf(2L, 3L))).isNull()
    }

    @Test
    fun `no comparison point without holdings`() {
        every { paper.buildHoldings(1L) } returns emptyList()

        assertThat(service.compare(1L, listOf(2L, 3L))).isNull()
    }

    @Test
    fun `optimize response keeps the previous flat fields and adds current`() {
        val json = ObjectMapper().registerKotlinModule().writeValueAsString(
            OptimizeResponse(OptimizationResult(stockIds = listOf(2L), expectedReturn = 0.1), current = null),
        )

        assertThat(json).contains("\"expectedReturn\":0.1", "\"stockIds\":[2]", "\"current\":null")
        assertThat(json).doesNotContain("\"result\"")
    }
}
