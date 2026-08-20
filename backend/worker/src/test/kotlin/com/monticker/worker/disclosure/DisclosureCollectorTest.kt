package com.monticker.worker.disclosure

import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class DisclosureCollectorTest {

    private val dartClient = mockk<DartClient>()
    private val jdbc       = mockk<JdbcTemplate>(relaxed = true)
    private val esOps      = mockk<ElasticsearchOperations>(relaxed = true)
    private val collector  = DisclosureCollector(dartClient, jdbc, esOps)

    private fun stubStockIds(vararg pairs: Pair<String, Long>) {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, Long>>>(), *anyVararg()) } returns
            pairs.map { it }
    }

    @Test
    fun `inserts new disclosure event`() {
        every { dartClient.isConfigured } returns false
        stubStockIds("005930" to 1L, "000660" to 2L, "035420" to 3L, "005380" to 4L, "051910" to 5L)
        every { jdbc.queryForObject(any<String>(), eq(Int::class.java), *anyVararg()) } returns 0
        every { jdbc.queryForObject(any<String>(), eq(Long::class.java), *anyVararg()) } returns 100L

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
    fun `uses dart client when configured, and computes importance score from report name`() {
        every { dartClient.isConfigured } returns true
        every { dartClient.fetchRecent(days = 1) } returns listOf(
            // "사업보고서" → importanceScore 80 (사업/반기보고서 규칙)
            DartDisclosure("20240101000099", "삼성전자", "005930", "사업보고서", "20240101")
        )
        stubStockIds("005930" to 1L)
        every { jdbc.queryForObject(any<String>(), eq(Int::class.java), *anyVararg()) } returns 0
        every { jdbc.queryForObject(any<String>(), eq(Long::class.java), *anyVararg()) } returns 200L

        collector.collect()

        verify { dartClient.fetchRecent(days = 1) }
        // importance_score(80)는 별도 스텁 없이 실제 importanceScore() 분기 로직이 계산한 값이며,
        // "사업보고서" → 80 은 DisclosureCollector.importanceScore()의 독립적인 스펙 값이다.
        verify {
            jdbc.update(
                match<String> { it.contains("INSERT INTO stock_events") },
                eq(1L), any<String>(), any<String>(), any<java.sql.Timestamp>(), eq(80), any<String>(),
            )
        }
    }

    @Test
    fun `merger disclosures get the highest importance score`() {
        every { dartClient.isConfigured } returns true
        every { dartClient.fetchRecent(days = 1) } returns listOf(
            // "합병" → importanceScore 90 (최고 우선순위 규칙)
            DartDisclosure("20240102000001", "SK하이닉스", "000660", "합병 결정", "20240102")
        )
        stubStockIds("000660" to 2L)
        every { jdbc.queryForObject(any<String>(), eq(Int::class.java), *anyVararg()) } returns 0
        every { jdbc.queryForObject(any<String>(), eq(Long::class.java), *anyVararg()) } returns 201L

        collector.collect()

        verify {
            jdbc.update(
                match<String> { it.contains("INSERT INTO stock_events") },
                eq(2L), any<String>(), any<String>(), any<java.sql.Timestamp>(), eq(90), any<String>(),
            )
        }
    }
}
