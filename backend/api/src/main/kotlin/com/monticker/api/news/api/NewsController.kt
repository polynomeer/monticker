package com.monticker.api.news.api

import com.monticker.api.news.application.NewsSearchResult
import com.monticker.api.news.application.NewsSearchService
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Validated
@RestController
class NewsController(private val newsSearchService: NewsSearchService) {

    /**
     * 종목별 뉴스 조회 + 선택적 키워드/기간/감성 필터.
     *
     * GET /api/stocks/{stockId}/news                     → 최신 20건
     * GET /api/stocks/{stockId}/news?query=반도체         → 전문 검색
     * GET /api/stocks/{stockId}/news?sentiment=POSITIVE  → 감성 필터
     */
    @GetMapping("/api/stocks/{stockId}/news")
    fun getStockNews(
        @PathVariable stockId: Long,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) sentiment: String?,
    ): ResponseEntity<List<NewsResponse>> {
        val results = newsSearchService.searchByStock(
            stockId   = stockId,
            query     = query,
            limit     = limit.coerceIn(1, 50),
            from      = from,
            to        = to,
        )
        return ResponseEntity.ok(results.map { NewsResponse.from(it) })
    }

    /**
     * 전 종목 크로스 키워드 검색.
     *
     * GET /api/news/search?query=금리인상
     * GET /api/news/search?query=AI&sentiment=POSITIVE
     */
    @GetMapping("/api/news/search")
    fun searchAll(
        @RequestParam query: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) sentiment: String?,
    ): ResponseEntity<List<NewsResponse>> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        val results = newsSearchService.searchAll(
            query     = query,
            limit     = limit.coerceIn(1, 50),
            from      = from,
            to        = to,
            sentiment = sentiment,
        )
        return ResponseEntity.ok(results.map { NewsResponse.from(it) })
    }
}

data class NewsResponse(
    val id: Long,
    val stockId: Long?,
    val title: String,
    val description: String?,
    val url: String,
    val source: String?,
    val publishedAt: Instant,
    val sentiment: String?,
    val score: Float?,
) {
    companion object {
        fun from(r: NewsSearchResult) = NewsResponse(
            id          = r.id,
            stockId     = r.stockId,
            title       = r.title,
            description = r.description,
            url         = r.url,
            source      = r.source,
            publishedAt = r.publishedAt,
            sentiment   = r.sentiment,
            score       = r.score,
        )
    }
}
