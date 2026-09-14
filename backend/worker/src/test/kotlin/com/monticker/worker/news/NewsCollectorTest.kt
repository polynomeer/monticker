package com.monticker.worker.news

import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class NewsCollectorTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val naverClient = mockk<NaverNewsClient>()
    private val sentimentAnalyzer = mockk<NewsSentimentAnalyzer>(relaxed = true)
    private val bloomFilter = mockk<NewsBloomFilter>(relaxed = true)

    // ADR-042: INSERT와 색인 이벤트가 TransactionTemplate 안에서 실행된다 — 콜백을 그대로 통과시킨다
    private val tx = mockk<TransactionTemplate>().apply {
        every { execute(any<TransactionCallback<Any?>>()) } answers { firstArg<TransactionCallback<Any?>>().doInTransaction(mockk(relaxed = true)) }
    }
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val collector = NewsCollector(naverClient, jdbc, sentimentAnalyzer, bloomFilter, events, tx)

    private fun stubInsertReturning(id: Long) {
        every { jdbc.query(match<String> { it.contains("INSERT INTO news_articles") }, any<RowMapper<Long>>(), *anyVararg()) } returns listOf(id)
    }
    private fun verifyInserted(atLeast: Int = 1) =
        verify(atLeast = atLeast) { jdbc.query(match<String> { it.contains("INSERT INTO news_articles") }, any<RowMapper<Long>>(), *anyVararg()) }

    @Test
    fun `uses mock generator when naver not configured`() {
        every { naverClient.isConfigured } returns false
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>()) } returns
            listOf(1L to "삼성전자")
        stubInsertReturning(10L)

        collector.collect()

        verify(exactly = 0) { naverClient.search(any(), any()) }
        verifyInserted()
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
        stubInsertReturning(11L)

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

        verify(exactly = 0) { jdbc.query(match<String> { it.contains("INSERT INTO news_articles") }, any<RowMapper<Long>>(), *anyVararg()) }
        verify(exactly = 0) { sentimentAnalyzer.analyze(any(), any()) }
        verify(exactly = 0) { bloomFilter.put(any()) }
    }

    @Test
    fun `collectForStock persists a single item and registers it in the bloom filter`() {
        every { bloomFilter.mightContain("https://news.com/new") } returns false
        stubInsertReturning(77L)
        val published = slot<Any>()
        every { events.publishEvent(capture(published)) } returns Unit

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
        // ADR-042: ES를 직접 쓰지 않고 완성된 문서를 색인 이벤트로 발행한다 (RETURNING id가 문서 id)
        val ev = published.captured as com.monticker.worker.search.SearchIndexEvent
        org.assertj.core.api.Assertions.assertThat(ev.index).isEqualTo("news_articles")
        org.assertj.core.api.Assertions.assertThat(ev.docId).isEqualTo("77")
        org.assertj.core.api.Assertions.assertThat(ev.payload!!["title"]).isEqualTo("새 뉴스")
        org.assertj.core.api.Assertions.assertThat(ev.payload!!["publishedAt"]).isInstanceOf(java.lang.Long::class.java)
    }

    @Test
    fun `a URL already in the table (ON CONFLICT DO NOTHING) neither publishes nor touches the bloom filter`() {
        every { bloomFilter.mightContain("https://news.com/dup") } returns false
        every { jdbc.query(match<String> { it.contains("INSERT INTO news_articles") }, any<RowMapper<Long>>(), *anyVararg()) } returns emptyList()

        val saved = collector.collectForStock(1L, NewsItem("중복", null, "https://news.com/dup", "src", java.time.Instant.now()))

        org.assertj.core.api.Assertions.assertThat(saved).isFalse()
        verify(exactly = 0) { events.publishEvent(any()) }
        verify(exactly = 0) { bloomFilter.put(any()) }
    }

    private fun assertThatSaved(saved: Boolean) {
        org.assertj.core.api.Assertions.assertThat(saved).isTrue()
    }
}
