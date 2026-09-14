package com.monticker.worker.detector

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import com.monticker.worker.search.SearchIndexEvent
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.context.ApplicationEventPublisher
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
    private val pushSender: com.monticker.worker.push.ExpoPushSender,
    // ingestion.source=internal일 때는 빈 ObjectProvider — 주입 없이도 동작 (ADR-005)
    private val eventKafkaProducer: org.springframework.beans.factory.ObjectProvider<com.monticker.worker.kafka.EventKafkaProducer>,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
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

        // ADR-042: INSERT(RETURNING id — 이전엔 SELECT를 한 번 더 했다)와 색인 이벤트를 한 트랜잭션에.
        // 커밋 후 Modulith가 Kafka search.index로 외부화하고 api의 SearchIndexConsumer가 색인한다.
        val insertedId: Long? = tx.execute {
            val id = jdbcTemplate.query(
                """
                INSERT INTO stock_events
                  (stock_id, event_type, title, description, event_time, importance_score, source_type, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SYSTEM', ?::jsonb, now(), now())
                RETURNING id
                """,
                { rs, _ -> rs.getLong("id") },
                event.stockId,
                event.eventType.name,
                event.title,
                event.description,
                Timestamp.from(event.eventTime),
                event.importanceScore,
                objectMapper.writeValueAsString(event.metadataJson),
            ).firstOrNull()
            if (id != null) {
                events.publishEvent(SearchIndexEvent.index(SEARCH_INDEX, id.toString(), searchPayload(
                    stockId = event.stockId, eventType = event.eventType.name, title = event.title,
                    description = event.description, eventTime = event.eventTime,
                    importanceScore = event.importanceScore, sourceType = "SYSTEM",
                )))
            }
            id
        }

        log.info("Event created: {} {} score={}", event.eventType, event.stockId, event.importanceScore)
        eventKafkaProducer.ifAvailable { it.publish(event) }
        sendEventPush(event)
        return true
    }

    companion object {
        const val SEARCH_INDEX = "stock_events"

        /** api `StockEventDocument` 매핑과 같은 형태 — 날짜는 epoch_millis, sentimentScore는 감지 이벤트에 없다. */
        fun searchPayload(
            stockId: Long, eventType: String, title: String, description: String?, eventTime: Instant,
            importanceScore: Int, sourceType: String,
        ): Map<String, Any?> = mapOf(
            "stockId"         to stockId,
            "eventType"       to eventType,
            "title"           to title,
            "description"     to description,
            "eventTime"       to eventTime.toEpochMilli(),
            "importanceScore" to importanceScore,
            "sentimentScore"  to null,
            "sourceType"      to sourceType,
        )
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
