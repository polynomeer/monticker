package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.infrastructure.TaxHarvestingLogRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class TaxOptimizerServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper()
    private val logRepo = mockk<TaxHarvestingLogRepository>()
    private val service = TaxOptimizerService(jdbc, objectMapper, logRepo)

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

    private fun stubCurrentPrice(stockId: Long, price: BigDecimal) {
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, stockId)
        } returns price
    }

    @Test
    fun `returns no candidates when every holding is currently above its average buy price`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("1000000"))
        stubCurrentPrice(100L, BigDecimal("60000")) // gain, not loss
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.candidates).isEmpty()
        assertThat(result.totalEstimatedTaxSaving).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `includes a holding as a candidate when its current price is below the average buy price`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("1000000"))
        stubCurrentPrice(100L, BigDecimal("40000"))
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
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
        stubCurrentPrice(100L, BigDecimal("40000")) // unrealised loss = 100,000
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
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
        stubCurrentPrice(100L, BigDecimal("48000")) // smaller loss: 20,000
        stubCurrentPrice(200L, BigDecimal("50000")) // bigger loss: 250,000
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "AAA", "name" to "A사")
        every { jdbc.queryForMap(any<String>(), eq(200L)) } returns mapOf("symbol" to "BBB", "name" to "B사")
        every { logRepo.save(any()) } answers { firstArg() }

        val result = service.findHarvestingCandidates(1L)

        assertThat(result.candidates.map { it.symbol }).containsExactly("BBB", "AAA")
    }

    @Test
    fun `skips a holding when its current price cannot be resolved`() {
        stubHoldings(listOf(mapOf("stock_id" to 100L, "qty" to 10, "avg_price" to BigDecimal("50000"))))
        stubRealizedGain(BigDecimal("100000"))
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L)
        } throws EmptyResultDataAccessException(1)
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
