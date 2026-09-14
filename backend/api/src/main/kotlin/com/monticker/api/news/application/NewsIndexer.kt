package com.monticker.api.news.application

import com.monticker.api.news.domain.NewsArticle
import com.monticker.api.news.infrastructure.NewsDocument
import com.monticker.api.news.infrastructure.NewsSearchRepository
import com.monticker.api.common.search.SearchReindexer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * news_articles DB → ES 동기화 (최근 10,000건) — 관리자 재색인·dev 플래그로만 (ADR-042 §5).
 * 신규 기사는 아직 Worker의 dual-write로 반영된다 (ADR-042 전환 2단계에서 이벤트 경로로).
 */
@Component
class NewsIndexer(
    private val jdbc: JdbcTemplate,
    private val newsSearchRepository: NewsSearchRepository,
) : SearchReindexer {
    override val index = "news_articles"
    override val documentClass: Class<*> = NewsDocument::class.java

    private val log = LoggerFactory.getLogger(javaClass)

    override fun reindexAll(): Int {
        val docs = fetchRecent(limit = 10_000).map { NewsDocument.from(it) }
        docs.chunked(500).forEach { newsSearchRepository.saveAll(it) }   // 500건씩 배치 upsert
        return docs.size
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
