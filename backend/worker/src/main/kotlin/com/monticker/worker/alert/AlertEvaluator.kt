package com.monticker.worker.alert

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class AlertRuleRow(
    val id: Long,
    val userId: Long,
    val stockId: Long,
    val ruleType: String,
    val conditionJson: String,
)

/**
 * 가격 알림 규칙 평가기.
 *
 * [Before] @Scheduled(fixedDelay=5000): 전체 alert_rules 폴링 → 각 종목 현재가 DB 재조회
 * [After]  @EventListener(TickProcessedEvent): 틱마다 해당 stockId 규칙만 평가,
 *          틱의 price를 그대로 사용하므로 DB 가격 재조회 불필요.
 *
 * 효과:
 *  - 5초 지연 → 실시간 (틱 단위)
 *  - 전체 룰 스캔 → stockId 필터 쿼리
 *  - DB price 재조회 제거 → 쿼리 수 감소
 *  - self-injection 해킹 제거
 */
@Component
class AlertEvaluator(
    private val jdbc: JdbcTemplate,
    private val pushSender: ExpoPushSender,
    private val esOps: ElasticsearchOperations,
    private val redis: StringRedisTemplate,
    private val mailSender: JavaMailSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    @EventListener
    @Async("alertDispatchExecutor")
    fun onTickProcessed(event: TickProcessedEvent) = processAlert(event.stockId, event.price)

    // AlertKafkaConsumer(role=alert)에서도 직접 호출한다
    fun processAlert(stockId: Long, price: java.math.BigDecimal) {
        try {
            val rules = fetchRulesForStock(stockId)
            for (rule in rules) evaluateRule(rule, price)
        } catch (e: Exception) {
            log.error("[AlertEvaluator] stockId={} 평가 오류: {}", stockId, e.message)
        }
    }

    private fun fetchRulesForStock(stockId: Long): List<AlertRuleRow> =
        jdbc.query(
            "SELECT id, user_id, stock_id, rule_type, condition_json FROM alert_rules WHERE stock_id = ? AND is_active = true",
            { rs, _ ->
                AlertRuleRow(
                    id            = rs.getLong("id"),
                    userId        = rs.getLong("user_id"),
                    stockId       = rs.getLong("stock_id"),
                    ruleType      = rs.getString("rule_type"),
                    conditionJson = rs.getString("condition_json"),
                )
            },
            stockId,
        )

    private fun evaluateRule(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val condition: Map<String, Any> = objectMapper.readValue(
            rule.conditionJson,
            object : TypeReference<Map<String, Any>>() {},
        )
        val triggered = when (rule.ruleType) {
            "PRICE_ABOVE" -> {
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                currentPrice > BigDecimal.valueOf(threshold)
            }
            "PRICE_BELOW" -> {
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                currentPrice < BigDecimal.valueOf(threshold)
            }
            "VOLUME_SURGE" -> {
                val surgeRatio = (condition["surgeRatio"] as? Number)?.toDouble() ?: 2.0
                val period     = (condition["period"] as? Number)?.toInt() ?: 20
                val row = jdbc.queryForMap(
                    """SELECT c.volume AS today_vol,
                              AVG(c2.volume) AS avg_vol
                       FROM candles_1d c
                       JOIN candles_1d c2 ON c2.stock_id = c.stock_id
                          AND c2.candle_time >= NOW() - INTERVAL '$period days'
                          AND c2.candle_time < DATE_TRUNC('day', NOW() AT TIME ZONE 'Asia/Seoul')
                       WHERE c.stock_id = ?
                         AND c.candle_time >= DATE_TRUNC('day', NOW() AT TIME ZONE 'Asia/Seoul')
                       LIMIT 1""",
                    rule.stockId,
                )
                val todayVol = (row["today_vol"] as? Number)?.toLong() ?: 0L
                val avgVol   = (row["avg_vol"] as? Number)?.toDouble() ?: 1.0
                avgVol > 0 && todayVol > avgVol * surgeRatio
            }
            else -> false
        }
        if (triggered) dispatchAlert(rule, currentPrice)
    }

    private fun dispatchAlert(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val cooldownKey = "alert:cooldown:${rule.id}"
        val acquired = redis.opsForValue().setIfAbsent(cooldownKey, "1", Duration.ofSeconds(600))
        if (acquired != true) return

        val message = buildMessage(rule, currentPrice)

        val historyId = jdbc.queryForObject(
            """
            INSERT INTO alert_histories (rule_id, stock_id, triggered_at, message, delivery_status)
            VALUES (?, ?, ?, ?, 'PENDING')
            RETURNING id
            """,
            Long::class.java,
            rule.id, rule.stockId,
            java.sql.Timestamp.from(Instant.now()),
            message,
        ) ?: return

        log.info("[AlertEvaluator] triggered: ruleId={} userId={} price={}", rule.id, rule.userId, currentPrice)

        val tokens = jdbc.queryForList(
            "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true",
            String::class.java,
            rule.userId,
        )
        if (tokens.isEmpty()) {
            sendEmailFallback(rule.userId, message)
            jdbc.update("UPDATE alert_histories SET delivery_status = 'EMAIL_FALLBACK' WHERE id = ?", historyId)
            return
        }

        val results = pushSender.send(tokens.map { token ->
            PushMessage(
                to    = token,
                title = "monticker 알림",
                body  = message,
                data  = mapOf("stockId" to rule.stockId, "ruleId" to rule.id),
            )
        })

        val status = if (results.all { it.status == "ok" }) "SENT" else "FAILED"
        jdbc.update("UPDATE alert_histories SET delivery_status = ? WHERE id = ?", status, historyId)
        log.info("[AlertEvaluator] push sent: userId={} status={}", rule.userId, status)

        indexToEs(historyId, rule, message, status, Instant.now())
    }

    private fun indexToEs(id: Long, rule: AlertRuleRow, message: String, status: String, triggeredAt: Instant) {
        try {
            esOps.save(AlertHistoryDocument(
                id             = id.toString(),
                ruleId         = rule.id,
                userId         = rule.userId,
                stockId        = rule.stockId.takeIf { it != 0L },
                ruleType       = rule.ruleType,
                message        = message,
                deliveryStatus = status,
                triggeredAt    = triggeredAt,
            ))
        } catch (e: Exception) {
            log.warn("[AlertEvaluator] ES indexing failed for historyId={}: {}", id, e.message)
        }
    }

    private fun sendEmailFallback(userId: Long, message: String) {
        try {
            val email = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ? AND deleted_at IS NULL",
                String::class.java, userId,
            ) ?: return
            val mail = SimpleMailMessage().apply {
                setTo(email)
                subject = "[monticker] 알림"
                text    = "$message\n\n설정한 알림 조건이 충족되었습니다."
            }
            mailSender.send(mail)
            log.info("[AlertEvaluator] email fallback sent: userId={}", userId)
        } catch (e: Exception) {
            log.warn("[AlertEvaluator] email fallback failed: userId={} {}", userId, e.message)
        }
    }

    private fun buildMessage(rule: AlertRuleRow, price: BigDecimal) = when (rule.ruleType) {
        "PRICE_ABOVE"  -> "가격이 ₩${price.toLong().formatKR()} 이상이 되었습니다"
        "PRICE_BELOW"  -> "가격이 ₩${price.toLong().formatKR()} 이하가 되었습니다"
        "VOLUME_SURGE" -> "거래량이 평균 대비 급증했습니다 (현재가 ₩${price.toLong().formatKR()})"
        else           -> "알림 조건 충족: ${rule.ruleType}"
    }

    private fun Long.formatKR() = "%,d".format(this)
}
