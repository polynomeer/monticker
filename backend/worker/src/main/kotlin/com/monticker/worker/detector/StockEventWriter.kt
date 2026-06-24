package com.monticker.worker.detector

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Executors

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
    private val pushSender: com.monticker.worker.push.ExpoPushSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // Push 알림은 hot path 밖에서 처리 — 별도 스레드 풀로 비동기 처리
    private val pushExecutor = Executors.newCachedThreadPool()

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
        sendEventPush(event)
        return true
    }

    private fun sendEventPush(event: DetectedEvent) {
        // hot path 탈출 — collect() 스레드를 블로킹하지 않음
        pushExecutor.submit {
            runCatching { sendEventPushAsync(event) }
                .onFailure { log.debug("Event push failed: {}", it.message) }
        }
    }

    private fun sendEventPushAsync(event: DetectedEvent) {
        // 이 stock_id를 관심종목으로 가진 user들의 device token 조회
        val tokens = jdbcTemplate.queryForList(
                """
                SELECT dt.token
                FROM device_tokens dt
                JOIN watchlist_items wi ON wi.stock_id = ?
                JOIN watchlist_groups wg ON wg.id = wi.watchlist_group_id
                WHERE wg.user_id = dt.user_id AND dt.is_active = true
                """,
                String::class.java,
                event.stockId,
            )
            if (tokens.isEmpty()) return

            val body = when (event.eventType) {
                DetectedEventType.VOLUME_SURGE -> "거래량이 급증했습니다"
                DetectedEventType.PRICE_SPIKE  -> "가격이 급등했습니다"
                DetectedEventType.PRICE_DROP   -> "가격이 급락했습니다"
            }

            val messages = tokens.map { token ->
                com.monticker.worker.push.PushMessage(
                    to    = token,
                    title = "monticker 이벤트 알림",
                    body  = "${event.title} — $body",
                    data  = mapOf("stockId" to event.stockId, "eventType" to event.eventType.name),
                )
            }
            pushSender.send(messages)
            log.info("Event push sent: {} tokens for stockId={}", tokens.size, event.stockId)
    }
}
