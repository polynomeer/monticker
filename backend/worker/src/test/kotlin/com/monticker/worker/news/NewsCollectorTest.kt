package com.monticker.worker.news

import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class NewsCollectorTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val naverClient = mockk<NaverNewsClient>()
    private val sentimentAnalyzer = mockk<NewsSentimentAnalyzer>(relaxed = true)
    private val bloomFilter = mockk<NewsBloomFilter>(relaxed = true)
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val collector = NewsCollector(naverClient, jdbc, sentimentAnalyzer, bloomFilter, esOps)

    @Test
    fun `uses mock generator when naver not configured`() {
        every { naverClient.isConfigured } returns false
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>()) } returns
            listOf(1L to "삼성전자")
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1

        collector.collect()

        verify(exactly = 0) { naverClient.search(any(), any()) }
        verify(atLeast = 1) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `uses naver client when configured`() {
        every { naverClient.isConfigured } returns true
        every { naverClient.search("삼성전자", display = 5) } returns listOf(
            NaverNewsItem("제목", "설명", "https://news.com/1", "Mon, 01 Jan 2024 09:00:00 +0900", "한국경제")
        )
        every { naverClient.parsePubDate(any()) } returns java.time.Instant.now()
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>()) } returns
            listOf(1L to "삼성전자")
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1

        collector.collect()

        verify { naverClient.search("삼성전자", display = 5) }
    }

    @Test
    fun `skips collection when no stocks`() {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>()) } returns emptyList()

        collector.collect()

        verify(exactly = 0) { naverClient.search(any(), any()) }
    }
}
