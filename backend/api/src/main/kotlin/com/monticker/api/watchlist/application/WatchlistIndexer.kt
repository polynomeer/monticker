package com.monticker.api.watchlist.application

import com.monticker.api.watchlist.infrastructure.WatchlistItemDocument
import com.monticker.api.watchlist.infrastructure.WatchlistSearchRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 앱 기동 시 watchlist_items DB → ES 동기화.
 * 이후 추가/삭제는 WatchlistService의 dual-write로 실시간 반영된다.
 */
@Component
class WatchlistIndexer(
    private val jdbc: JdbcTemplate,
    private val searchRepository: WatchlistSearchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    @Async
    fun indexAll() {
        try {
            val docs = fetchAll()
            if (docs.isEmpty()) {
                log.info("No watchlist items to index in Elasticsearch")
                return
            }
            docs.chunked(500).forEach { searchRepository.saveAll(it) }
            log.info("Elasticsearch watchlist index synced: {} documents", docs.size)
        } catch (e: Exception) {
            log.warn("Elasticsearch watchlist indexing skipped: {}", e.message)
        }
    }

    private fun fetchAll(): List<WatchlistItemDocument> =
        jdbc.query(
            """
            SELECT wi.id, wi.memo, wi.target_price, wi.sort_order, wi.created_at,
                   wg.id AS group_id, wg.user_id, wg.name AS group_name,
                   s.id  AS stock_id, s.symbol, s.name AS stock_name, s.sector
            FROM watchlist_items wi
            JOIN watchlist_groups wg ON wg.id = wi.group_id
            JOIN stocks s ON s.id = wi.stock_id
            ORDER BY wi.id
            """,
        ) { rs, _ ->
            WatchlistItemDocument(
                id          = rs.getLong("id").toString(),
                userId      = rs.getLong("user_id"),
                groupId     = rs.getLong("group_id"),
                groupName   = rs.getString("group_name"),
                stockId     = rs.getLong("stock_id"),
                symbol      = rs.getString("symbol"),
                stockName   = rs.getString("stock_name"),
                sector      = rs.getString("sector"),
                memo        = rs.getString("memo"),
                targetPrice = rs.getDouble("target_price").takeIf { it != 0.0 },
                createdAt   = rs.getTimestamp("created_at").toInstant(),
            )
        }
}
