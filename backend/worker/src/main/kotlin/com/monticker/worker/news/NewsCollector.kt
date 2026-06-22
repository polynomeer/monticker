package com.monticker.worker.news

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

@Component
class NewsCollector(
    private val naverClient: NaverNewsClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 300_000) // 5분마다
    fun collect() {
        val stocks = fetchStocks()
        if (stocks.isEmpty()) return

        stocks.forEach { stock ->
            try {
                collectForStock(stock)
            } catch (e: Exception) {
                log.error("News collection failed for stock {}: {}", stock.second, e.message)
            }
        }
    }

    private fun collectForStock(stock: Pair<Long, String>) {
        val (stockId, stockName) = stock

        val items = if (naverClient.isConfigured) {
            naverClient.search(stockName, display = 5)
        } else {
            MockNewsGenerator.generate(stockName, count = 3)
        }

        var saved = 0
        for (item in items) {
            val publishedAt = runCatching {
                naverClient.parsePubDate(item.pubDate)
            }.getOrDefault(Instant.now())

            val inserted = jdbc.update(
                """
                INSERT INTO news_articles (stock_id, title, description, url, source, published_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (url) DO NOTHING
                """,
                stockId,
                item.title.take(500),
                item.description.take(1000),
                item.link.take(1000),
                item.source.take(100),
                Timestamp.from(publishedAt),
            )
            if (inserted > 0) saved++
        }

        if (saved > 0) {
            log.info("Saved {} news articles for {}", saved, stockName)
        }
    }

    private fun fetchStocks(): List<Pair<Long, String>> =
        jdbc.query("SELECT id, name FROM stocks WHERE is_active = true") { rs, _ ->
            rs.getLong("id") to rs.getString("name")
        }
}
