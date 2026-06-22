package com.monticker.api.alert.application

import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
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
        rule.isActive = false
        rule.updatedAt = Instant.now()
        alertRuleRepository.save(rule)
    }

    @Transactional(readOnly = true)
    fun getHistories(userId: Long): List<AlertHistoryResponse> {
        return jdbc.query(
            """
            SELECT ah.id, ah.rule_id, ah.stock_id, ah.triggered_at, ah.message, ah.delivery_status,
                   s.symbol, s.name as stock_name
            FROM alert_histories ah
            LEFT JOIN stocks s ON s.id = ah.stock_id
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ?
            ORDER BY ah.triggered_at DESC
            LIMIT 50
            """,
            { rs, _ ->
                AlertHistoryResponse(
                    id = rs.getLong("id"),
                    ruleId = rs.getLong("rule_id"),
                    stockId = rs.getLong("stock_id"),
                    symbol = rs.getString("symbol"),
                    stockName = rs.getString("stock_name"),
                    triggeredAt = rs.getTimestamp("triggered_at").toInstant(),
                    message = rs.getString("message"),
                    deliveryStatus = rs.getString("delivery_status"),
                )
            },
            userId,
        )
    }
}

data class AlertHistoryResponse(
    val id: Long,
    val ruleId: Long,
    val stockId: Long,
    val symbol: String?,
    val stockName: String?,
    val triggeredAt: java.time.Instant,
    val message: String,
    val deliveryStatus: String,
)
