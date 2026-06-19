package com.monticker.worker.alert

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class AlertEvaluator(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AlertEvaluator::class.java)

    @Scheduled(fixedDelay = 5000)
    fun evaluate() {
        try {
            val rules = fetchActiveRules()
            for (rule in rules) {
                evaluateRule(rule)
            }
        } catch (e: Exception) {
            log.error("Alert evaluation error", e)
        }
    }

    private fun fetchActiveRules(): List<AlertRuleRow> {
        return jdbc.query(
            """
            SELECT id, user_id, stock_id, rule_type, condition_json
            FROM alert_rules
            WHERE is_active = true
            """,
        ) { rs, _ ->
            AlertRuleRow(
                id            = rs.getLong("id"),
                userId        = rs.getLong("user_id"),
                stockId       = rs.getLong("stock_id"),
                ruleType      = rs.getString("rule_type"),
                conditionJson = rs.getString("condition_json"),
            )
        }
    }

    private fun evaluateRule(rule: AlertRuleRow) {
        val condition: Map<String, Any> = objectMapper.readValue(
            rule.conditionJson,
            object : TypeReference<Map<String, Any>>() {},
        )

        val currentPrice = fetchCurrentPrice(rule.stockId) ?: return

        val triggered = when (rule.ruleType) {
            "PRICE_ABOVE" -> {
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                currentPrice > BigDecimal.valueOf(threshold)
            }
            "PRICE_BELOW" -> {
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                currentPrice < BigDecimal.valueOf(threshold)
            }
            else -> false
        }

        if (triggered) {
            dispatchAlert(rule, currentPrice)
        }
    }

    private fun fetchCurrentPrice(stockId: Long): BigDecimal? {
        return try {
            jdbc.queryForObject(
                """
                SELECT close
                FROM candles_1m
                WHERE stock_id = ?
                ORDER BY candle_time DESC
                LIMIT 1
                """,
                BigDecimal::class.java,
                stockId,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun dispatchAlert(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val alreadyFired = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM alert_histories
            WHERE rule_id = ? AND triggered_at > NOW() - INTERVAL '10 minutes'
            """,
            Int::class.java,
            rule.id,
        ) ?: 0

        if (alreadyFired > 0) return

        val message = buildMessage(rule, currentPrice)

        jdbc.update(
            """
            INSERT INTO alert_histories (rule_id, stock_id, triggered_at, message, delivery_status)
            VALUES (?, ?, ?, ?, 'PENDING')
            """,
            rule.id,
            rule.stockId,
            java.sql.Timestamp.from(Instant.now()),
            message,
        )

        log.info("Alert triggered: ruleId={} userId={} message={}", rule.id, rule.userId, message)
        // Expo push delivery → Week 7
    }

    private fun buildMessage(rule: AlertRuleRow, price: BigDecimal): String {
        return when (rule.ruleType) {
            "PRICE_ABOVE" -> "가격이 ₩${price.toLong().formatKR()} 이상이 되었습니다"
            "PRICE_BELOW" -> "가격이 ₩${price.toLong().formatKR()} 이하가 되었습니다"
            else -> "알림 조건 충족: ${rule.ruleType}"
        }
    }

    private fun Long.formatKR() = "%,d".format(this)
}

data class AlertRuleRow(
    val id: Long,
    val userId: Long,
    val stockId: Long,
    val ruleType: String,
    val conditionJson: String,
)
