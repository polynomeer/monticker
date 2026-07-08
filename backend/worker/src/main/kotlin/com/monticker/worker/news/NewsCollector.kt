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
    private val sentimentAnalyzer: NewsSentimentAnalyzer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 30분마다 활성 종목 전체 뉴스 수집
    @Scheduled(fixedDelay = 1_800_000)
    fun collect() {
        val stocks = fetchActiveStocks()
        if (stocks.isEmpty()) return

        var total = 0
        for ((stockId, stockName) in stocks) {
            val items = fetchNews(stockName)
            for (item in items) {
                if (persist(stockId, item)) total++
            }
        }
        log.info("News sync done: stocks={} inserted={} source={}",
            stocks.size, total, if (naverClient.isConfigured) "naver" else "mock")
    }

    /**
     * 단일 뉴스 아이템을 영구 저장한다.
     * ON CONFLICT (url) DO NOTHING — 중복 삽입은 무시.
     */
    fun collectForStock(stockId: Long, item: NewsItem): Boolean = persist(stockId, item)

    private fun fetchActiveStocks(): List<Pair<Long, String>> =
        jdbc.query(
            "SELECT id, name FROM stocks WHERE is_active = true ORDER BY id LIMIT 200",
        ) { rs, _ -> rs.getLong("id") to rs.getString("name") }

    private fun fetchNews(stockName: String): List<NewsItem> {
        return if (naverClient.isConfigured) {
            naverClient.search(stockName, display = 5).map { n ->
                NewsItem(
                    title       = n.title,
                    description = n.description,
                    link        = n.link,
                    source      = n.source,
                    publishedAt = runCatching { naverClient.parsePubDate(n.pubDate) }.getOrNull(),
                )
            }
        } else {
            MockNewsGenerator.generate(stockName, count = 3).map { n ->
                NewsItem(
                    title       = n.title,
                    description = n.description,
                    link        = n.link,
                    source      = n.source,
                    publishedAt = Instant.now(),
                )
            }
        }
    }

    private fun persist(stockId: Long, item: NewsItem): Boolean {
        val publishedAt = item.publishedAt ?: Instant.now()
        val sentiment   = sentimentAnalyzer.analyze(item.title, item.description)

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
            log.debug("Saved news stockId={} title={} sentiment={}", stockId, item.title.take(60), sentiment)
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
