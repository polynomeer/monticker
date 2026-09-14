package com.monticker.api.event.application

import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.StockEventDocument
import com.monticker.api.event.infrastructure.StockEventSearchRepository
import com.monticker.api.common.search.SearchReindexer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

@Component
class EventIndexer(
    private val jdbc: JdbcTemplate,
    private val searchRepository: StockEventSearchRepository,
) : SearchReindexer {
    override val index = "stock_events"
    override val documentClass: Class<*> = StockEventDocument::class.java

    private val log = LoggerFactory.getLogger(javaClass)

    /** 최근 10,000건 — 관리자 재색인·dev 플래그로만 (ADR-042 §5). 신규 이벤트는 아직 Worker dual-write. */
    override fun reindexAll(): Int {
        val docs = fetchRecent(limit = 10_000).map { StockEventDocument.from(it) }
        docs.chunked(500).forEach { searchRepository.saveAll(it) }
        return docs.size
    }

    private fun fetchRecent(limit: Int): List<StockEvent> =
        jdbc.query(
            """
            SELECT id, stock_id, event_type, title, description,
                   event_time, importance_score, sentiment_score, source_type, created_at, updated_at
            FROM stock_events
            ORDER BY event_time DESC
            LIMIT ?
            """,
            { rs, _ ->
                StockEvent(
                    id             = rs.getLong("id"),
                    stockId        = rs.getLong("stock_id"),
                    eventType      = com.monticker.api.event.domain.EventType.valueOf(rs.getString("event_type")),
                    title          = rs.getString("title"),
                    description    = rs.getString("description"),
                    eventTime      = rs.getTimestamp("event_time").toInstant(),
                    importanceScore = rs.getInt("importance_score"),
                    sentimentScore = rs.getBigDecimal("sentiment_score"),
                    sourceType     = rs.getString("source_type"),
                    createdAt      = rs.getTimestamp("created_at").toInstant(),
                    updatedAt      = rs.getTimestamp("updated_at").toInstant(),
                )
            },
            limit,
        )
}
