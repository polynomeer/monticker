package com.monticker.api.news.application

import com.monticker.api.news.domain.NewsArticle
import com.monticker.api.news.infrastructure.NewsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NewsServiceTest {

    private val repo = mockk<NewsRepository>()
    private val service = NewsService(repo)

    @Test
    fun `returns news for stockId`() {
        val article = NewsArticle(1L, 1L, "삼성전자 신고가", null, "https://example.com/1", "한국경제", Instant.now(), null)
        every { repo.findByStockId(1L, 20) } returns listOf(article)

        val result = service.getNews(1L)

        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo("삼성전자 신고가")
        verify { repo.findByStockId(1L, 20) }
    }

    @Test
    fun `returns empty list when no news`() {
        every { repo.findByStockId(99L, 20) } returns emptyList()

        assertThat(service.getNews(99L)).isEmpty()
    }
}
