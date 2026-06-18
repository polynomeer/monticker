package com.monticker.worker.detector

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

enum class DetectedEventType {
    PRICE_SPIKE, PRICE_DROP, VOLUME_SURGE
}

data class DetectedEvent(
    val stockId: Long,
    val eventType: DetectedEventType,
    val title: String,
    val description: String,
    val eventTime: Instant,
    val importanceScore: Int,
    val metadataJson: Map<String, Any>,
)

@Component
class StockEventWriter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(event: DetectedEvent): Boolean {
        // Check duplicate: same stock_id + event_type within same minute
        val minuteStart = event.eventTime.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val minuteEnd = minuteStart.plusSeconds(60)

        val exists = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM stock_events
            WHERE stock_id = ? AND event_type = ? AND event_time >= ? AND event_time < ?
            """,
            Int::class.java,
            event.stockId, event.eventType.name,
            Timestamp.from(minuteStart), Timestamp.from(minuteEnd),
        )

        if ((exists ?: 0) > 0) {
            log.debug("Duplicate event skipped: {} {} @ {}", event.stockId, event.eventType, minuteStart)
            return false
        }

        jdbcTemplate.update(
            """
            INSERT INTO stock_events
              (stock_id, event_type, title, description, event_time, importance_score, source_type, metadata_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'SYSTEM', ?::jsonb, now(), now())
            """,
            event.stockId,
            event.eventType.name,
            event.title,
            event.description,
            Timestamp.from(event.eventTime),
            event.importanceScore,
            objectMapper.writeValueAsString(event.metadataJson),
        )

        log.info("Event created: {} {} score={}", event.eventType, event.stockId, event.importanceScore)
        return true
    }
}
