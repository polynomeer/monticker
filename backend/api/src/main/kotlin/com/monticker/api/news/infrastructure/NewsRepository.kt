package com.monticker.api.news.infrastructure

import com.monticker.api.news.domain.NewsArticle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class NewsRepository(private val jdbc: JdbcTemplate) {

    fun findByStockId(stockId: Long, limit: Int = 20): List<NewsArticle> =
        jdbc.query(
            """
            SELECT id, stock_id, title, description, url, source, published_at, sentiment
            FROM news_articles
            WHERE stock_id = ?
            ORDER BY published_at DESC
            LIMIT ?
            """,
            { rs, _ ->
                NewsArticle(
                    id          = rs.getLong("id"),
                    stockId     = rs.getLong("stock_id"),
                    title       = rs.getString("title"),
                    description = rs.getString("description"),
                    url         = rs.getString("url"),
                    source      = rs.getString("source"),
                    publishedAt = rs.getTimestamp("published_at").toInstant(),
                    sentiment   = rs.getString("sentiment"),
                )
            },
            stockId, limit,
        )
}
