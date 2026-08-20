package com.monticker.api.event.application

import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.StockEventRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class EventTimelineService(
    private val eventRepository: StockEventRepository,
    private val jdbc: JdbcTemplate,
) {
    fun getTimeline(
        stockId: Long,
        from: Instant = Instant.now().minus(24, ChronoUnit.HOURS),
        to: Instant = Instant.now(),
    ): List<StockEvent> =
        eventRepository.findByStockIdAndTimeRange(stockId, from, to)

    fun getSectorSummary(hours: Int): List<SectorEventSummary> {
        return jdbc.query(
            """
            SELECT s.sector, se.event_type, COUNT(*) as cnt, MAX(se.importance_score) as max_score
            FROM stock_events se
            JOIN stocks s ON s.id = se.stock_id
            WHERE se.event_time > NOW() - INTERVAL '$hours hours'
              AND s.sector IS NOT NULL
            GROUP BY s.sector, se.event_type
            ORDER BY max_score DESC, cnt DESC
            LIMIT 30
            """,
        ) { rs, _ ->
            SectorEventSummary(
                sector    = rs.getString("sector"),
                eventType = rs.getString("event_type"),
                count     = rs.getInt("cnt"),
                maxScore  = rs.getInt("max_score"),
            )
        }
    }

    fun getRecentEvents(limit: Int): List<StockEvent> =
        eventRepository.findByOrderByEventTimeDesc(
            org.springframework.data.domain.PageRequest.of(0, limit)
        )
}

data class SectorEventSummary(
    val sector: String,
    val eventType: String,
    val count: Int,
    val maxScore: Int,
)
