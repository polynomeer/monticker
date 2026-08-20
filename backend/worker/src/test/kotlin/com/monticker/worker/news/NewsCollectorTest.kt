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

    @Test
    fun `skips news item already seen in bloom filter (no DB insert, no sentiment call)`() {
        every { naverClient.isConfigured } returns true
        every { naverClient.search("삼성전자", display = 5) } returns listOf(
            NaverNewsItem("제목", "설명", "https://news.com/dup", "Mon, 01 Jan 2024 09:00:00 +0900", "한국경제")
        )
        every { naverClient.parsePubDate(any()) } returns java.time.Instant.now()
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>()) } returns
            listOf(1L to "삼성전자")
        every { bloomFilter.mightContain("https://news.com/dup") } returns true

        collector.collect()

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT INTO news_articles") }, *anyVararg()) }
        verify(exactly = 0) { sentimentAnalyzer.analyze(any(), any()) }
        verify(exactly = 0) { bloomFilter.put(any()) }
    }

    @Test
    fun `collectForStock persists a single item and registers it in the bloom filter`() {
        every { bloomFilter.mightContain("https://news.com/new") } returns false
        every { jdbc.update(match<String> { it.contains("INSERT INTO news_articles") }, *anyVararg()) } returns 1

        val saved = collector.collectForStock(
            stockId = 1L,
            item = NewsItem(
                title = "새 뉴스",
                description = "설명",
                link = "https://news.com/new",
                source = "한국경제",
                publishedAt = java.time.Instant.now(),
            ),
        )

        assertThatSaved(saved)
        verify { bloomFilter.put("https://news.com/new") }
    }

    private fun assertThatSaved(saved: Boolean) {
        org.assertj.core.api.Assertions.assertThat(saved).isTrue()
    }
}
