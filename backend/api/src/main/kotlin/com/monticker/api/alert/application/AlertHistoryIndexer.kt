package com.monticker.api.alert.application

import com.monticker.api.alert.infrastructure.AlertHistoryDocument
import com.monticker.api.alert.infrastructure.AlertHistorySearchRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 앱 기동 시 alert_histories 최근 50,000건 → ES 동기화.
 * 이후 신규 이력은 AlertEvaluator(worker) dual-write로 실시간 반영된다.
 */
@Component
class AlertHistoryIndexer(
    private val jdbc: JdbcTemplate,
    private val searchRepository: AlertHistorySearchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun indexRecent() {
        try {
            val docs = fetchRecent(limit = 50_000)
            if (docs.isEmpty()) {
                log.info("No alert histories to index in Elasticsearch")
                return
            }
            docs.chunked(500).forEach { searchRepository.saveAll(it) }
            log.info("Elasticsearch alert history index synced: {} documents", docs.size)
        } catch (e: Exception) {
            log.warn("Elasticsearch alert history indexing skipped: {}", e.message)
        }
    }

    private fun fetchRecent(limit: Int): List<AlertHistoryDocument> =
        jdbc.query(
            """
            SELECT ah.id, ah.rule_id, ah.stock_id, ah.triggered_at, ah.message, ah.delivery_status,
                   ar.user_id, ar.rule_type
            FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            ORDER BY ah.triggered_at DESC
            LIMIT ?
            """,
            { rs, _ ->
                AlertHistoryDocument(
                    id             = rs.getLong("id").toString(),
                    ruleId         = rs.getLong("rule_id"),
                    userId         = rs.getLong("user_id"),
                    stockId        = rs.getLong("stock_id").takeIf { it != 0L },
                    ruleType       = rs.getString("rule_type"),
                    message        = rs.getString("message"),
                    deliveryStatus = rs.getString("delivery_status"),
                    triggeredAt    = rs.getTimestamp("triggered_at").toInstant(),
                )
            },
            limit,
        )
}
