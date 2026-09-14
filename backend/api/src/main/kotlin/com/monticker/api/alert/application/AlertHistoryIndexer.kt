package com.monticker.api.alert.application

import com.monticker.api.alert.infrastructure.AlertHistoryDocument
import com.monticker.api.alert.infrastructure.AlertHistorySearchRepository
import com.monticker.api.common.search.SearchReindexer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * alert_histories 최근 50,000건 → ES 동기화 — 관리자 재색인·dev 플래그로만 (ADR-042 §5).
 * 신규 이력은 아직 api AlertService·worker AlertDispatcher의 dual-write로 반영된다 (전환 3단계).
 */
@Component
class AlertHistoryIndexer(
    private val jdbc: JdbcTemplate,
    private val searchRepository: AlertHistorySearchRepository,
) : SearchReindexer {
    override val index = "alert_histories"
    override val documentClass: Class<*> = AlertHistoryDocument::class.java

    private val log = LoggerFactory.getLogger(javaClass)

    override fun reindexAll(): Int {
        val docs = fetchRecent(limit = 50_000)
        docs.chunked(500).forEach { searchRepository.saveAll(it) }
        return docs.size
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
