package com.monticker.api.watchlist.application

import com.monticker.api.watchlist.infrastructure.WatchlistItemDocument
import com.monticker.api.watchlist.infrastructure.WatchlistSearchRepository
import com.monticker.api.common.search.SearchReindexer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * watchlist_items DB → ES 전량 동기화 (ADR-042 §5: 기동 시가 아니라 관리자 재색인·dev 플래그로만).
 * 실시간 반영은 WatchlistService가 발행하는 SearchIndexEvent → SearchIndexConsumer 경로다.
 */
@Component
class WatchlistIndexer(
    private val jdbc: JdbcTemplate,
    private val searchRepository: WatchlistSearchRepository,
) : SearchReindexer {
    companion object { const val INDEX = "watchlist_items" }
    override val index = INDEX
    override val documentClass: Class<*> = WatchlistItemDocument::class.java

    private val log = LoggerFactory.getLogger(javaClass)

    override fun reindexAll(): Int {
        val docs = fetchAll()
        docs.chunked(500).forEach { searchRepository.saveAll(it) }
        return docs.size
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
