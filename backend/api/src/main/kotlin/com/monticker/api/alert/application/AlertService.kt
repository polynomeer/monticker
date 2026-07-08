package com.monticker.api.alert.application

import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class AlertService(
    private val alertRuleRepository: AlertRuleRepository,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
) {
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
}

data class AlertStatsResponse(
    val totalFired: Int, val totalSent: Int, val totalFailed: Int,
    val successRate: Double, val activeRules: Int,
    val recentFires: List<AlertFireStat>
)
data class AlertFireStat(val date: String, val count: Int)
