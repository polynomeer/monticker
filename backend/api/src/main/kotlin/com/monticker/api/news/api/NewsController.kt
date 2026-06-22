package com.monticker.api.news.api

import com.monticker.api.news.application.NewsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/stocks/{stockId}/news")
class NewsController(private val newsService: NewsService) {

    @GetMapping
    fun getNews(
        @PathVariable stockId: Long,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<NewsResponse>> {
        val news = newsService.getNews(stockId, limit.coerceIn(1, 50))
        return ResponseEntity.ok(news.map { NewsResponse.from(it) })
    }
}

data class NewsResponse(
    val id: Long,
    val title: String,
    val description: String?,
    val url: String,
    val source: String?,
    val publishedAt: Instant,
    val sentiment: String?,
) {
    companion object {
        fun from(a: com.monticker.api.news.domain.NewsArticle) = NewsResponse(
            id          = a.id,
            title       = a.title,
            description = a.description,
            url         = a.url,
            source      = a.source,
            publishedAt = a.publishedAt,
            sentiment   = a.sentiment,
        )
    }
}
