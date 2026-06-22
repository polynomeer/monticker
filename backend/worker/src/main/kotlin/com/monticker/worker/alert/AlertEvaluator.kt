package com.monticker.worker.alert

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

data class AlertRuleRow(
    val id: Long,
    val userId: Long,
    val stockId: Long,
    val ruleType: String,
    val conditionJson: String,
)

@Component
class AlertEvaluator(
    private val jdbc: JdbcTemplate,
    private val pushSender: ExpoPushSender,
) {
    private val log = LoggerFactory.getLogger(AlertEvaluator::class.java)
    private val objectMapper = ObjectMapper()

    @Scheduled(fixedDelay = 5000)
    fun evaluate() {
        try {
            val rules = fetchActiveRules()
            for (rule in rules) evaluateRule(rule)
        } catch (e: Exception) {
            log.error("Alert evaluation error", e)
        }
    }

    private fun fetchActiveRules(): List<AlertRuleRow> =
        jdbc.query(
            "SELECT id, user_id, stock_id, rule_type, condition_json FROM alert_rules WHERE is_active = true",
        ) { rs, _ ->
            AlertRuleRow(
                id            = rs.getLong("id"),
                userId        = rs.getLong("user_id"),
                stockId       = rs.getLong("stock_id"),
                ruleType      = rs.getString("rule_type"),
                conditionJson = rs.getString("condition_json"),
            )
        }

    private fun evaluateRule(rule: AlertRuleRow) {
        val condition: Map<String, Any> = objectMapper.readValue(
            rule.conditionJson,
            object : TypeReference<Map<String, Any>>() {}
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

        if (triggered) dispatchAlert(rule, currentPrice)
    }

    private fun fetchCurrentPrice(stockId: Long): BigDecimal? = try {
        jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java,
            stockId,
        )
    } catch (e: Exception) { null }

    private fun dispatchAlert(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val alreadyFired = jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_histories WHERE rule_id = ? AND triggered_at > NOW() - INTERVAL '10 minutes'",
            Int::class.java,
            rule.id,
        ) ?: 0
        if (alreadyFired > 0) return

        val message = buildMessage(rule, currentPrice)

        val historyId = jdbc.queryForObject(
            """
            INSERT INTO alert_histories (rule_id, stock_id, triggered_at, message, delivery_status)
            VALUES (?, ?, ?, ?, 'PENDING')
            RETURNING id
            """,
            Long::class.java,
            rule.id,
            rule.stockId,
            java.sql.Timestamp.from(Instant.now()),
            message,
        ) ?: return

        log.info("Alert triggered: ruleId={} userId={} message={}", rule.id, rule.userId, message)

        val tokens = jdbc.queryForList(
            "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true",
            String::class.java,
            rule.userId,
        )

        if (tokens.isEmpty()) {
            log.debug("No device tokens for userId={}", rule.userId)
            return
        }

        val pushMessages = tokens.map { token ->
            PushMessage(
                to    = token,
                title = "monticker 알림",
                body  = message,
                data  = mapOf("stockId" to rule.stockId, "ruleId" to rule.id),
            )
        }

        val results = pushSender.send(pushMessages)
        val allOk = results.all { it.status == "ok" }
        val newStatus = if (allOk) "SENT" else "FAILED"

        jdbc.update(
            "UPDATE alert_histories SET delivery_status = ? WHERE id = ?",
            newStatus,
            historyId,
        )

        log.info("Push sent: userId={} tokens={} status={}", rule.userId, tokens.size, newStatus)
    }

    private fun buildMessage(rule: AlertRuleRow, price: BigDecimal): String = when (rule.ruleType) {
        "PRICE_ABOVE" -> "가격이 ₩${price.toLong().formatKR()} 이상이 되었습니다"
        "PRICE_BELOW" -> "가격이 ₩${price.toLong().formatKR()} 이하가 되었습니다"
        else          -> "알림 조건 충족: ${rule.ruleType}"
    }

    private fun Long.formatKR() = "%,d".format(this)
}
