package com.monticker.api.news.application

import com.monticker.api.news.infrastructure.NewsDocument
import com.monticker.api.news.infrastructure.NewsRepository
import com.monticker.api.news.infrastructure.NewsSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.SearchHit
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class NewsSearchService(
    private val esOps: ElasticsearchOperations,
    private val newsRepository: NewsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 특정 종목 뉴스 중 키워드 검색.
     * query=null 이면 최신순 전체 반환.
     */
    fun searchByStock(
        stockId: Long,
        query: String?,
        limit: Int = 20,
        from: Instant? = null,
        to: Instant? = null,
    ): List<NewsSearchResult> {
        if (query.isNullOrBlank() && from == null && to == null) {
            return newsRepository.findByStockId(stockId, limit).map { NewsSearchResult.from(it) }
        }
        return try {
            searchFromEs(stockId = stockId, query = query, limit = limit, from = from, to = to)
        } catch (e: Exception) {
            log.warn("ES news search failed, falling back to DB: {}", e.message)
            newsRepository.findByStockId(stockId, limit).map { NewsSearchResult.from(it) }
        }
    }

    /**
     * 전 종목 크로스 키워드 검색.
     * 뉴스 타임라인 / 글로벌 이슈 탐색용.
     */
    fun searchAll(
        query: String,
        limit: Int = 20,
        from: Instant? = null,
        to: Instant? = null,
        sentiment: String? = null,
    ): List<NewsSearchResult> {
        return try {
            searchFromEs(
                stockId   = null,
                query     = query,
                limit     = limit,
                from      = from,
                to        = to,
                sentiment = sentiment,
            )
        } catch (e: Exception) {
            log.warn("ES global news search failed: {}", e.message)
            emptyList()
        }
    }

    // ── ES 쿼리 빌더 ─────────────────────────────────────────────────────────

    private fun searchFromEs(
        stockId: Long?,
        query: String?,
        limit: Int,
        from: Instant?,
        to: Instant?,
        sentiment: String? = null,
    ): List<NewsSearchResult> {
        val nativeQuery = NativeQuery.builder()
            .withQuery { q ->
                q.bool { b ->
                    // 키워드 검색: title(제목) 가중치를 description보다 높게
                    if (!query.isNullOrBlank()) {
                        b.must { m ->
                            m.multiMatch { mm ->
                                mm.query(query)
                                    .fields("title^3", "description^1")
                                    .analyzer("nori_analyzer")
                            }
                        }
                    }
                    // 종목 필터
                    if (stockId != null) {
                        b.filter { f -> f.term { t -> t.field("stockId").value(stockId) } }
                    }
                    // 날짜 범위 필터
                    if (from != null || to != null) {
                        b.filter { f ->
                            f.range { r ->
                                r.date { d ->
                                    var dr = d.field("publishedAt")
                                    if (from != null) dr = dr.gte(from.toEpochMilli().toString())
                                    if (to != null)   dr = dr.lte(to.toEpochMilli().toString())
                                    dr
                                }
                            }
                        }
                    }
                    // 감성 필터
                    if (!sentiment.isNullOrBlank()) {
                        b.filter { f -> f.term { t -> t.field("sentiment").value(sentiment) } }
                    }
                    b
                }
            }
            .withMaxResults(limit)
            .build()

        val hits = esOps.search(nativeQuery, NewsDocument::class.java)
        return hits.map { hit -> NewsSearchResult.from(hit) }.toList()
    }
}

// ── 결과 DTO ─────────────────────────────────────────────────────────────────

data class NewsSearchResult(
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
        fun from(article: com.monticker.api.news.domain.NewsArticle) = NewsSearchResult(
            id          = article.id,
            stockId     = article.stockId,
            title       = article.title,
            description = article.description,
            url         = article.url,
            source      = article.source,
            publishedAt = article.publishedAt,
            sentiment   = article.sentiment,
            score       = null,
        )

        fun from(hit: SearchHit<NewsDocument>) = hit.content.let { doc ->
            NewsSearchResult(
                id          = doc.id.toLong(),
                stockId     = doc.stockId,
                title       = doc.title,
                description = doc.description,
                url         = doc.url,
                source      = doc.source,
                publishedAt = doc.publishedAt,
                sentiment   = doc.sentiment,
                score       = hit.score,
            )
        }
    }
}
