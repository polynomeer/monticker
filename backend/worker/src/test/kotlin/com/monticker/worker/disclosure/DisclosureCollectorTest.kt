package com.monticker.worker.disclosure

import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class DisclosureCollectorTest {

    private val dartClient = mockk<DartClient>()
    private val jdbc       = mockk<JdbcTemplate>(relaxed = true)
    private val collector  = DisclosureCollector(dartClient, jdbc)

    private fun stubStockIds(vararg pairs: Pair<String, Long>) {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, Long>>>(), *anyVararg()) } returns
            pairs.map { it }
    }

    @Test
    fun `inserts new disclosure event`() {
        every { dartClient.isConfigured } returns false
        stubStockIds("005930" to 1L, "000660" to 2L, "035420" to 3L, "005380" to 4L, "051910" to 5L)
        every { jdbc.queryForObject(any<String>(), eq(Int::class.java), *anyVararg()) } returns 0

        collector.collect()

        verify(atLeast = 1) { jdbc.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
    }

    @Test
    fun `skips duplicate disclosure by rceptNo`() {
        every { dartClient.isConfigured } returns false
        stubStockIds("005930" to 1L, "000660" to 2L, "035420" to 3L, "005380" to 4L, "051910" to 5L)
        // already exists
        every { jdbc.queryForObject(any<String>(), eq(Int::class.java), *anyVararg()) } returns 1

        collector.collect()

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
    }

    @Test
    fun `skips stocks not in DB`() {
        every { dartClient.isConfigured } returns false
        // no stocks mapped
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, Long>>>(), *anyVararg()) } returns emptyList()

        collector.collect()

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
    }

    @Test
    fun `uses dart client when configured`() {
        every { dartClient.isConfigured } returns true
        every { dartClient.fetchRecent(days = 1) } returns listOf(
            DartDisclosure("20240101000099", "삼성전자", "005930", "사업보고서", "20240101")
        )
        stubStockIds("005930" to 1L)
        every { jdbc.queryForObject(any<String>(), eq(Int::class.java), *anyVararg()) } returns 0

        collector.collect()

        verify { dartClient.fetchRecent(days = 1) }
        verify { jdbc.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
    }
}
