package com.monticker.worker.news

import com.monticker.worker.common.DistributedLock
import org.slf4j.LoggerFactory
import com.monticker.worker.search.SearchIndexEvent
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.context.ApplicationEventPublisher
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
    private val bloomFilter: NewsBloomFilter,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1_800_000)
    @DistributedLock(name = "news-collector", ttlSeconds = 1_500)
    fun collect() {
        val stocks = fetchActiveStocks()
        if (stocks.isEmpty()) return

        var total = 0
        var skipped = 0
        for ((stockId, stockName) in stocks) {
            val items = fetchNews(stockName)
            for (item in items) {
                if (bloomFilter.mightContain(item.link)) { skipped++; continue }
                if (persist(stockId, item)) total++
            }
        }
        log.info("News sync done: stocks={} inserted={} bloom_skipped={} bf_stats=[{}] source={}",
            stocks.size, total, skipped, bloomFilter.stats(), if (naverClient.isConfigured) "naver" else "mock")
    }

    fun collectForStock(stockId: Long, item: NewsItem): Boolean {
        if (bloomFilter.mightContain(item.link)) return false
        return persist(stockId, item)
    }

    private fun fetchActiveStocks(): List<Pair<Long, String>> =
        jdbc.query(
            "SELECT id, name FROM stocks WHERE is_active = true ORDER BY id LIMIT 200",
        ) { rs, _ -> rs.getLong("id") to rs.getString("name") }

    private fun fetchNews(stockName: String): List<NewsItem> =
        if (naverClient.isConfigured) {
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

    private fun persist(stockId: Long, item: NewsItem): Boolean {
        val publishedAt = item.publishedAt ?: Instant.now()
        val sentiment   = sentimentAnalyzer.analyze(item.title, item.description)

        // ADR-042: DB INSERT와 색인 이벤트를 한 트랜잭션에 — 롤백되면 이벤트도 사라지고, 커밋되면 Modulith가
        // 커밋 후 Kafka search.index로 외부화한다(실패 시 event_publication에 남아 5분 뒤 재전송).
        // RETURNING id: 이전엔 ON CONFLICT DO NOTHING 뒤에 url로 SELECT를 한 번 더 했다.
        val id: Long? = tx.execute {
            val inserted = jdbc.query(
                """
                INSERT INTO news_articles (stock_id, title, description, url, source, published_at, sentiment)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (url) DO NOTHING
                RETURNING id
                """.trimIndent(),
                { rs, _ -> rs.getLong("id") },
                stockId,
                item.title.take(500),
                item.description?.take(1000),
                item.link.take(1000),
                item.source.take(100),
                Timestamp.from(publishedAt),
                sentiment,
            ).firstOrNull()
            if (inserted != null) {
                events.publishEvent(SearchIndexEvent.index(INDEX, inserted.toString(), mapOf(
                    "stockId"     to stockId,
                    "title"       to item.title,
                    "description" to item.description,
                    "url"         to item.link,
                    "source"      to item.source,
                    "publishedAt" to publishedAt.toEpochMilli(),   // api NewsDocument: epoch_millis
                    "sentiment"   to sentiment,
                )))
            }
            inserted
        }

        if (id != null) {
            bloomFilter.put(item.link)
            log.debug("Saved news id={} stockId={} title={} sentiment={}", id, stockId, item.title.take(60), sentiment)
        }
        return id != null
    }

    companion object { const val INDEX = "news_articles" }
}

data class NewsItem(
    val title: String,
    val description: String?,
    val link: String,
    val source: String,
    val publishedAt: Instant?,
)
