package com.monticker.worker.newsalert

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.search.SearchIndexEvent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-100 — 새 뉴스·공시 하나를 그 종목을 관심종목에 넣은 사용자 각자의 알림 이력에 적재하고 알린다(ADR-090과 같은 사용자 소유 행).
 *
 * ```
 * NewsCollector / DisclosureCollector (수집 트랜잭션)
 *   └ news_articles | stock_events INSERT + publish NewsAlertCandidateEvent ──► worker_outbox.event_publication
 *                                                             │ 커밋 후 (@ApplicationModuleListener: 비동기, 새 트랜잭션)
 * NewsAlertFanout.on ◄────────────────────────────────────────┘
 *   신선도·중요도 → 아니면 끝
 *   pg_advisory_xact_lock(뉴스 팬아웃)                       // 시간당 상한을 세고 쓰는 동안 다른 팬아웃과 겹치지 않게
 *   받는 사람 = 그 종목을 관심종목에 넣은 사용자(탈퇴 제외, 뉴스·공시 알림을 끈 사람 제외) + 최근 1시간 뉴스 이력 수
 *   사용자마다: 상한 판정 → INSERT alert_histories … ON CONFLICT (user_id, dedup_key) DO NOTHING RETURNING id
 *     새 행만 → SearchIndexEvent(alert_histories, ruleType=NEWS)                     (외부화 → api 색인)
 *              → NewsAlertNotifyEvent (상한 안일 때만)                               (내부 → NewsAlertDelivery → 발송 정책)
 * ```
 *
 * - 이력 행과 두 이벤트를 한 트랜잭션에 쓴다. 발송(푸시·메일)은 커밋 뒤 별도 리스너가 한다 — 트랜잭션 안에서 외부로 보내는 일이 없다.
 * - 멱등: 판정은 사전 조회가 아니라 유니크 인덱스다. 재전송돼도 이미 있는 사용자는 건너뛰고(행·이벤트 없음) 새로 관심종목에 넣은
 *   사용자만 받는다.
 * - **채널·방해 금지 시간은 여기서 정하지 않는다.** 발송 시점에 `NotificationPolicy`가 NEWS 종류로 정한다(ADR-082/093). 여기서는
 *   "이 종류를 받겠다"(news_alert_push 또는 news_alert_email)만 본다 — 뉴스는 양이 많아 끈 사람의 알림함까지 채우지 않는다.
 * - 소유: 받는 사람은 **그 사용자 자신의 관심종목**에서만 나온다. 다른 사람의 관심종목·보유 종목은 보지 않는다.
 */
@Component
class NewsAlertFanout(
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CATEGORY = "NEWS"
        const val SEARCH_INDEX = "alert_histories"
        /** 알림까지 나간 행 — 발송 결과는 이 행에 되돌려 쓰지 않는다(ADR-090과 같음) */
        const val STATUS_QUEUED = "QUEUED"
        /** 시간당 알림 상한을 넘어 이력만 남긴 행 */
        const val STATUS_CAPPED = "CAPPED"
        /** 뉴스 팬아웃 전역 advisory lock 키("news" ASCII) — 인스턴스·리스너 스레드가 여럿이어도 상한을 넘겨 쓰지 않게 */
        const val LOCK_KEY = 0x6E657773L

        fun notifyKey(event: NewsAlertCandidateEvent, userId: Long) = "${event.historyKey()}:u$userId"
    }

    @ApplicationModuleListener
    fun on(event: NewsAlertCandidateEvent) {
        fanOut(event, Instant.now())
    }

    /** 새로 적재한 사용자 수를 돌려준다(재전송·지난 기사면 0). */
    fun fanOut(event: NewsAlertCandidateEvent, now: Instant): Int {
        if (!NewsAlertRules.importanceQualifies(event.kind, event.importanceScore)) return 0
        if (!NewsAlertRules.fresh(event.kind, event.publishedAt(), now)) {
            meterRegistry.counter("news_alert_skipped_total", "reason", "stale").increment()
            log.debug("[NewsAlert] 지난 사건이라 알리지 않음: {} publishedAt={}", event.historyKey(), event.publishedAt())
            return 0
        }
        return tx.execute { fanOutInTx(event, now) } ?: 0
    }

    private data class Recipient(val userId: Long, val recorded: Int, val notified: Int)

    private fun fanOutInTx(event: NewsAlertCandidateEvent, now: Instant): Int {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", RowCallbackHandler { }, LOCK_KEY)

        val recipients = jdbc.query(
            """
            SELECT a.user_id, COALESCE(c.recorded, 0) AS recorded, COALESCE(c.notified, 0) AS notified
            FROM (
                SELECT DISTINCT wg.user_id
                FROM watchlist_items wi
                JOIN watchlist_groups wg ON wg.id = wi.group_id
                JOIN users u ON u.id = wg.user_id AND u.deleted_at IS NULL
                LEFT JOIN notification_preferences np ON np.user_id = wg.user_id
                WHERE wi.stock_id = ?
                  AND COALESCE(np.news_alert_push OR np.news_alert_email, true)
            ) a
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS recorded,
                       COUNT(*) FILTER (WHERE ah.delivery_status = '$STATUS_QUEUED') AS notified
                FROM alert_histories ah
                WHERE ah.user_id = a.user_id AND ah.category = '$CATEGORY' AND ah.triggered_at >= ?
            ) c ON true
            ORDER BY a.user_id
            """.trimIndent(),
            { rs, _ -> Recipient(rs.getLong("user_id"), rs.getInt("recorded"), rs.getInt("notified")) },
            event.stockId, Timestamp.from(now.minus(NewsAlertRules.CAP_WINDOW)),
        )
        if (recipients.isEmpty()) return 0

        val stockName = jdbc.queryForList("SELECT name FROM stocks WHERE id = ?", String::class.java, event.stockId)
            .firstOrNull() ?: "관심종목"
        val label = if (event.kind == NewsAlertKind.DISCLOSURE) "공시" else "뉴스"
        val message = if (event.kind == NewsAlertKind.DISCLOSURE) event.title else "[뉴스] ${event.title}"
        val payload = objectMapper.writeValueAsString(mapOf(
            "kind" to event.kind.name, "sourceId" to event.sourceId, "url" to event.url, "source" to event.source,
            "publishedAt" to event.publishedAt().toString(), "importanceScore" to event.importanceScore,
        ))

        var inserted = 0
        var capped = 0
        var skipped = 0
        for (r in recipients) {
            val decision = NewsAlertRules.decide(event.kind, r.recorded, r.notified)
            if (decision == NewsAlertRules.Decision.SKIP) { skipped++; continue }
            val status = if (decision == NewsAlertRules.Decision.NOTIFY) STATUS_QUEUED else STATUS_CAPPED
            val historyId = jdbc.queryForList(
                """
                INSERT INTO alert_histories (user_id, category, dedup_key, stock_id, triggered_at, message, payload_json, delivery_status)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (user_id, dedup_key) WHERE user_id IS NOT NULL DO NOTHING
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                r.userId, CATEGORY, event.historyKey(), event.stockId, Timestamp.from(now), message, payload, status,
            ).firstOrNull() ?: continue
            inserted++
            events.publishEvent(SearchIndexEvent.index(SEARCH_INDEX, historyId.toString(), mapOf(
                "ruleId"         to null,
                "userId"         to r.userId,
                "stockId"        to event.stockId,
                "ruleType"       to CATEGORY,
                "message"        to message,
                "deliveryStatus" to status,
                "triggeredAt"    to now.toEpochMilli(),
            )))
            if (decision == NewsAlertRules.Decision.RECORD_ONLY) { capped++; continue }
            events.publishEvent(NewsAlertNotifyEvent(
                userId = r.userId,
                historyId = historyId,
                title = "$stockName 새 $label",
                body = event.title,
                dedupKey = notifyKey(event, r.userId),
                data = buildMap {
                    put("type", CATEGORY)
                    put("kind", event.kind.name)
                    put("stockId", event.stockId)
                    put("historyId", historyId)
                    event.url?.let { put("url", it) }
                },
            ))
        }
        if (capped > 0) meterRegistry.counter("news_alert_skipped_total", "reason", "notify_cap").increment(capped.toDouble())
        if (skipped > 0) meterRegistry.counter("news_alert_skipped_total", "reason", "record_cap").increment(skipped.toDouble())
        log.info("[NewsAlert] {} stockId={} audience={} newlyRecorded={} capped={} skipped={}",
            event.historyKey(), event.stockId, recipients.size, inserted, capped, skipped)
        return inserted
    }
}
