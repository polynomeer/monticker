package com.monticker.api.alert.application

import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertHistoryDocument
import com.monticker.api.alert.infrastructure.AlertHistorySearchRepository
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class AlertService(
    private val alertRuleRepository: AlertRuleRepository,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val alertHistorySearchRepository: AlertHistorySearchRepository,
    private val esOps: ElasticsearchOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Transactional(readOnly = true)
    fun getRules(userId: Long): List<AlertRule> =
        alertRuleRepository.findAllByUserIdAndIsActiveTrue(userId)

    fun createRule(
        userId: Long,
        stockId: Long?,
        ruleType: AlertRuleType,
        condition: Map<String, Any>,
    ): AlertRule {
        val rule = AlertRule(
            userId = userId,
            stockId = stockId,
            ruleType = ruleType,
            conditionJson = objectMapper.writeValueAsString(condition),
        )
        return alertRuleRepository.save(rule)
    }

    fun deactivateRule(userId: Long, ruleId: Long) {
        val rule = alertRuleRepository.findById(ruleId).orElseThrow {
            NoSuchElementException("Alert rule not found: $ruleId")
        }
        require(rule.userId == userId) { "Access denied" }
        rule.deactivate()
        alertRuleRepository.save(rule)
    }

    @Transactional(readOnly = true)
    fun getStats(userId: Long): AlertStatsResponse {
        val counts = jdbc.queryForMap("""
            SELECT
              COUNT(*) FILTER (WHERE ah.delivery_status = 'SENT')    AS sent,
              COUNT(*) FILTER (WHERE ah.delivery_status = 'FAILED')  AS failed,
              COUNT(*) FILTER (WHERE ah.delivery_status = 'PENDING') AS pending
            FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ?
        """, userId)

        val sent    = (counts["sent"]    as? Number)?.toInt() ?: 0
        val failed  = (counts["failed"]  as? Number)?.toInt() ?: 0
        val pending = (counts["pending"] as? Number)?.toInt() ?: 0
        val total   = sent + failed + pending
        val rate    = if (sent + failed > 0) sent.toDouble() / (sent + failed) * 100 else 0.0

        val activeRules = jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_rules WHERE user_id = ? AND is_active = true",
            Int::class.java, userId
        ) ?: 0

        val daily = jdbc.query("""
            SELECT DATE(ah.triggered_at AT TIME ZONE 'Asia/Seoul') AS d, COUNT(*) AS cnt
            FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ? AND ah.triggered_at > NOW() - INTERVAL '7 days'
            GROUP BY d ORDER BY d
        """, { rs, _ -> AlertFireStat(rs.getString("d"), rs.getInt("cnt")) }, userId)

        return AlertStatsResponse(total, sent, failed, rate, activeRules, daily)
    }

    /**
     * 내 알림 이력을 키워드·종목·타입·상태·날짜로 검색 (ES).
     */
    @Transactional(readOnly = true)
    fun searchHistory(
        userId: Long,
        query: String? = null,
        stockId: Long? = null,
        ruleType: String? = null,
        deliveryStatus: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 20,
    ): List<AlertHistoryResult> {
        return try {
            val nativeQuery = NativeQuery.builder()
                .withQuery { q ->
                    q.bool { b ->
                        b.filter { f -> f.term { t -> t.field("userId").value(userId) } }
                        if (!query.isNullOrBlank()) {
                            b.must { m ->
                                m.match { mm ->
                                    mm.field("message").query(query).analyzer("nori_analyzer")
                                }
                            }
                        }
                        if (stockId != null) {
                            b.filter { f -> f.term { t -> t.field("stockId").value(stockId) } }
                        }
                        if (!ruleType.isNullOrBlank()) {
                            b.filter { f -> f.term { t -> t.field("ruleType").value(ruleType) } }
                        }
                        if (!deliveryStatus.isNullOrBlank()) {
                            b.filter { f -> f.term { t -> t.field("deliveryStatus").value(deliveryStatus) } }
                        }
                        if (from != null || to != null) {
                            b.filter { f ->
                                f.range { r ->
                                    r.date { d ->
                                        var dr = d.field("triggeredAt")
                                        if (from != null) dr = dr.gte(from.toEpochMilli().toString())
                                        if (to != null)   dr = dr.lte(to.toEpochMilli().toString())
                                        dr
                                    }
                                }
                            }
                        }
                        b
                    }
                }
                .withMaxResults(limit.coerceIn(1, 100))
                .build()

            esOps.search(nativeQuery, AlertHistoryDocument::class.java)
                .map { hit -> AlertHistoryResult.from(hit.content, hit.score) }
                .toList()
        } catch (e: Exception) {
            log.warn("ES alert history search failed for userId={}: {}", userId, e.message)
            emptyList()
        }
    }
}

data class AlertStatsResponse(
    val totalFired: Int, val totalSent: Int, val totalFailed: Int,
    val successRate: Double, val activeRules: Int,
    val recentFires: List<AlertFireStat>
)
data class AlertFireStat(val date: String, val count: Int)

data class AlertHistoryResult(
    val id: Long,
    val ruleId: Long,
    val stockId: Long?,
    val ruleType: String,
    val message: String,
    val deliveryStatus: String,
    val triggeredAt: Instant,
    val score: Float?,
) {
    companion object {
        fun from(doc: AlertHistoryDocument, score: Float) = AlertHistoryResult(
            id             = doc.id.toLong(),
            ruleId         = doc.ruleId,
            stockId        = doc.stockId,
            ruleType       = doc.ruleType,
            message        = doc.message,
            deliveryStatus = doc.deliveryStatus,
            triggeredAt    = doc.triggeredAt,
            score          = score,
        )
    }
}
