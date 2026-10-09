package com.monticker.worker.detector

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry,
    private val pushSender: com.monticker.worker.push.ExpoPushSender,
    // ingestion.source=internal일 때는 빈 ObjectProvider — 주입 없이도 동작 (ADR-005)
    private val eventKafkaProducer: org.springframework.beans.factory.ObjectProvider<com.monticker.worker.kafka.EventKafkaProducer>,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val preferences: com.monticker.worker.notification.NotificationPreferenceReader,
    // 관심종목 급등·급락·거래량 급증 푸시의 운영 스위치(WATCHLIST_EVENT_PUSH_ENABLED). 끄면 이벤트 행·색인·Kafka는 그대로, 푸시만 안 나간다.
    @Value("\${notify.watchlist-event-push.enabled:true}") private val pushEnabled: Boolean = true,
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
                // ADR-051 — watch rule(모의 자동주문) 소비자용 아웃박스. 색인 이벤트와 같은 트랜잭션에 둔다:
                // stock_events 행과 두 발행 기록이 한 커밋에 묶여야 "행은 있는데 아무도 모르는" 이벤트가 없다.
                events.publishEvent(StockEventDetectedEvent.of(id, event))
            }
            id
        }

        log.info("Event created: {} {} score={}", event.eventType, event.stockId, event.importanceScore)
        meterRegistry.counter("stock_events_written_total", "source", "SYSTEM", "type", event.eventType.name).increment()   // Realtime 대시보드 "이벤트 탐지"
        eventKafkaProducer.ifAvailable { it.publish(event) }
        sendEventPush(event)
        return true
    }

    companion object {
        const val SEARCH_INDEX = "stock_events"

        /**
         * 이 종목을 관심종목에 넣은 사용자의 활성 기기 토큰. 바인딩: stockId.
         * 예전 쿼리는 존재하지 않는 컬럼 `wi.watchlist_group_id`로 조인해 매번 SQL 오류였고(실제 컬럼은 `group_id`, V3),
         * 오류가 runCatching + debug 로그에 묻혀 관심종목 급등·급락·거래량 급증 푸시가 한 번도 나가지 않았다.
         * 같은 종목이 여러 그룹에 있어도 토큰은 한 번 — 탈퇴한 사용자는 제외.
         */
        const val WATCHER_DEVICE_TOKENS_SQL = """
            SELECT DISTINCT dt.user_id, dt.token
            FROM watchlist_items wi
            JOIN watchlist_groups wg ON wg.id = wi.group_id
            JOIN device_tokens dt ON dt.user_id = wg.user_id AND dt.is_active = true
            JOIN users u ON u.id = wg.user_id AND u.deleted_at IS NULL
            WHERE wi.stock_id = ?
        """

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
        if (!pushEnabled) return
        // hot path 탈출 — collect() 스레드를 블로킹하지 않음
        pushExecutor.submit {
            runCatching { sendEventPushAsync(event) }
                // debug였다 — 그래서 존재하지 않는 컬럼으로 매번 실패하던 것이 운영 로그에 보이지 않았다
                .onFailure { log.warn("Event push failed for stockId={}: {}", event.stockId, it.message) }
        }
    }

    private fun sendEventPushAsync(event: DetectedEvent) {
        // 이 stock_id를 관심종목으로 가진 user들의 device token 조회
        val rows = jdbcTemplate.query(
                WATCHER_DEVICE_TOKENS_SQL,
                { rs, _ -> rs.getLong("user_id") to rs.getString("token") },
                event.stockId,
            )
            if (rows.isEmpty()) return

            // ADR-082 — 사용자 알림 설정: 거래량 급증은 "거래량 급증", 급등·급락은 "가격 알림"의 푸시 선택을 따른다(이 경로는 푸시만 보낸다).
            val category = if (event.eventType == DetectedEventType.VOLUME_SURGE)
                com.monticker.worker.notification.NotificationCategory.VOLUME_SURGE
            else com.monticker.worker.notification.NotificationCategory.PRICE_ALERT
            val prefs = preferences.forUsers(rows.map { it.first })
            val tokens = rows.filter { (userId, _) ->
                com.monticker.worker.notification.NotificationPolicy.plan(prefs.getValue(userId), category).push
            }.map { it.second }.distinct()
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
