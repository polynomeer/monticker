package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.infrastructure.TaxHarvestingLogRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class TaxOptimizerServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper()
    private val logRepo = mockk<TaxHarvestingLogRepository>()
    private val queryService = TaxHarvestingQueryService(jdbc)
    private val service = TaxOptimizerService(queryService, objectMapper, logRepo)

    private fun stubHoldings(rows: List<Map<String, Any>>) {
        every {
            jdbc.queryForList(match<String> { it.contains("GROUP BY stock_id") }, any<Long>())
        } returns rows
    }

    private fun stubRealizedGain(amount: BigDecimal) {
        every {
            jdbc.queryForObject(match<String> { it.contains("date_trunc('year'") }, BigDecimal::class.java, any<Long>(), any<Long>())
        } returns amount
    }

    private fun stubBatchPrice(vararg stockPrices: Pair<Long, BigDecimal>) {
        every {
            jdbc.queryForList(match<String> { it.contains("DISTINCT ON") }, *anyVararg())
        } returns stockPrices.map { (stockId, price) ->
            mapOf("stock_id" to stockId, "close" to price)
        }
    }

    private fun stubBatchStockInfo(vararg infos: Triple<Long, String, String>) {
        every {
            jdbc.queryForList(match<String> { it.contains("FROM stocks WHERE id IN") }, *anyVararg())
        } returns infos.map { (id, symbol, name) ->
            mapOf("id" to id, "symbol" to symbol, "name" to name)
        }
    }

    @Test
    fun `returns no candidates when every holding is currently above its average buy price`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("1000000"))
        stubBatchPrice(100L to BigDecimal("60000")) // gain, not loss
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.candidates).isEmpty()
        assertThat(result.totalEstimatedTaxSaving).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `includes a holding as a candidate when its current price is below the average buy price`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("1000000"))
        stubBatchPrice(100L to BigDecimal("40000"))
        stubBatchStockInfo(Triple(100L, "005930", "삼성전자"))
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.candidates).hasSize(1)
        // unrealizedLoss = (40000 - 50000) * 10 = -100,000
        assertThat(result.candidates[0].unrealizedLoss).isEqualByComparingTo(BigDecimal("-100000"))
    }

    @Test
    fun `estimated tax saving is the lesser of unrealised loss and realised gain times the tax rate`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("50000")) // smaller than the unrealised loss magnitude
        stubBatchPrice(100L to BigDecimal("40000")) // unrealised loss = 100,000
        stubBatchStockInfo(Triple(100L, "005930", "삼성전자"))
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        // min(100000, 50000) * 0.22 = 11,000.00
        assertThat(result.candidates[0].estimatedTaxSaving).isEqualByComparingTo(BigDecimal("11000.00"))
    }

    @Test
    fun `candidates are sorted by estimated tax saving descending`() {
        stubHoldings(listOf(
            mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000")),
            mapOf("stock_id" to 200L, "qty" to 5, "avg_price" to BigDecimal("100000")),
        ))
        stubRealizedGain(BigDecimal("10000000"))
        stubBatchPrice(100L to BigDecimal("48000"), 200L to BigDecimal("50000"))
        stubBatchStockInfo(Triple(100L, "AAA", "A사"), Triple(200L, "BBB", "B사"))
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.candidates.map { it.symbol }).containsExactly("BBB", "AAA")
    }

    @Test
    fun `skips a holding when its current price cannot be resolved`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("100000"))
        // batch price returns empty — no price data for this stock
        every {
            jdbc.queryForList(match<String> { it.contains("DISTINCT ON") }, *anyVararg())
        } returns emptyList()
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun `response always includes the simulation-only disclaimer`() {
        stubHoldings(emptyList())
        stubRealizedGain(BigDecimal.ZERO)
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.disclaimer).contains("모의투자").contains("세무 신고에 사용할 수 없습니다")
    }

    @Test
    fun `persists a tax harvesting log with the computed totals`() {
        stubHoldings(emptyList())
        stubRealizedGain(BigDecimal("500000"))
        val slot = io.mockk.slot<com.monticker.api.analytics.domain.TaxHarvestingLog>()
        every { logRepo.save(capture(slot)) } answers { slot.captured }

        service.findHarvestingCandidates(1L)

        assertThat(slot.captured.userId).isEqualTo(1L)
        assertThat(slot.captured.realizedGainYtd).isEqualByComparingTo(BigDecimal("500000"))
        assertThat(slot.captured.taxRateAssumed).isEqualByComparingTo(BigDecimal("0.22"))
    }
}
