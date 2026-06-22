package com.monticker.worker.news

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * NewsCollector stub — collects news items for a stock and persists them with
 * Claude-powered sentiment scores via [NewsSentimentAnalyzer].
 *
 * The actual news-fetch logic (e.g. RSS, Naver News API) is wired in separately;
 * this component owns the DB write path and sentiment enrichment.
 */
@Component
class NewsCollector(
    private val jdbc: JdbcTemplate,
    private val sentimentAnalyzer: NewsSentimentAnalyzer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Persists a single news item for the given stock.
     * Returns true if a new row was inserted (ON CONFLICT DO NOTHING).
     */
    fun collectForStock(stockId: Long, item: NewsItem): Boolean {
        val publishedAt = item.publishedAt ?: Instant.now()
        val sentiment = sentimentAnalyzer.analyze(item.title, item.description)

        val inserted = jdbc.update(
            """
            INSERT INTO news_articles (stock_id, title, description, url, source, published_at, sentiment)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (url) DO NOTHING
            """.trimIndent(),
            stockId,
            item.title.take(500),
            item.description?.take(1000),
            item.link.take(1000),
            item.source.take(100),
            Timestamp.from(publishedAt),
            sentiment,
        )

        if (inserted > 0) {
            log.debug("Saved news for stockId={} title={} sentiment={}", stockId, item.title.take(60), sentiment)
        }
        return inserted > 0
    }
}

data class NewsItem(
    val title: String,
    val description: String?,
    val link: String,
    val source: String,
    val publishedAt: Instant?,
)
