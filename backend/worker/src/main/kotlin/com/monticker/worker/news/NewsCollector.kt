package com.monticker.worker.news

import com.monticker.worker.common.DistributedLock
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
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
    private val esOps: ElasticsearchOperations,
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

        // DB 저장 — ON CONFLICT DO NOTHING (url unique)
        val rows = jdbc.update(
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

        if (rows > 0) {
            bloomFilter.put(item.link)

            // ES dual-write — DB pk는 RETURNING으로 가져올 수 없어서 url 기반 id 생성
            val esId = fetchIdByUrl(item.link)
            if (esId != null) {
                indexToEs(esId, stockId, item, publishedAt, sentiment)
            }

            log.debug("Saved news stockId={} title={} sentiment={}", stockId, item.title.take(60), sentiment)
        }
        return rows > 0
    }

    private fun fetchIdByUrl(url: String): Long? =
        runCatching {
            jdbc.queryForObject(
                "SELECT id FROM news_articles WHERE url = ?",
                Long::class.java,
                url,
            )
        }.getOrNull()

    private fun indexToEs(id: Long, stockId: Long, item: NewsItem, publishedAt: Instant, sentiment: String?) {
        try {
            val doc = NewsDocument(
                id          = id.toString(),
                stockId     = stockId,
                title       = item.title,
                description = item.description,
                url         = item.link,
                source      = item.source,
                publishedAt = publishedAt,
                sentiment   = sentiment,
            )
            esOps.save(doc)
        } catch (e: Exception) {
            // ES 인덱싱 실패는 경고만 — DB 저장은 이미 완료
            log.warn("ES indexing failed for news id={}: {}", id, e.message)
        }
    }
}

data class NewsItem(
    val title: String,
    val description: String?,
    val link: String,
    val source: String,
    val publishedAt: Instant?,
)
