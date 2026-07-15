package com.monticker.api.news.application

import com.monticker.api.news.domain.NewsArticle
import com.monticker.api.news.infrastructure.NewsDocument
import com.monticker.api.news.infrastructure.NewsSearchRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * 앱 기동 시 news_articles DB → ES 동기화 (최근 10,000건).
 * 이후 신규 기사는 Worker의 dual-write로 실시간 인덱싱된다.
 */
@Component
class NewsIndexer(
    private val jdbc: JdbcTemplate,
    private val newsSearchRepository: NewsSearchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun indexRecent() {
        try {
            val articles = fetchRecent(limit = 10_000)
            if (articles.isEmpty()) {
                log.info("No news articles to index in Elasticsearch")
                return
            }
            val docs = articles.map { NewsDocument.from(it) }
            // 500건씩 배치 upsert
            docs.chunked(500).forEach { batch ->
                newsSearchRepository.saveAll(batch)
            }
            log.info("Elasticsearch news index synced: {} documents", docs.size)
        } catch (e: Exception) {
            log.warn("Elasticsearch news indexing skipped: {}", e.message)
        }
    }

    private fun fetchRecent(limit: Int): List<NewsArticle> =
        jdbc.query(
            """
            SELECT id, stock_id, title, description, url, source, published_at, sentiment
            FROM news_articles
            ORDER BY published_at DESC
            LIMIT ?
            """,
            { rs, _ ->
                NewsArticle(
                    id          = rs.getLong("id"),
                    stockId     = rs.getLong("stock_id").takeIf { it != 0L },
                    title       = rs.getString("title"),
                    description = rs.getString("description"),
                    url         = rs.getString("url"),
                    source      = rs.getString("source"),
                    publishedAt = rs.getTimestamp("published_at").toInstant(),
                    sentiment   = rs.getString("sentiment"),
                )
            },
            limit,
        )
}
