package com.monticker.worker.alert

import org.slf4j.LoggerFactory
import com.monticker.worker.search.SearchIndexEvent
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * ADR-044 — 발동한 알림의 **발송**: 쿨다운 → alert_histories 기록 → 푸시/이메일 → 상태 갱신 → ES.
 *
 * 평가(AlertEvaluator)와 분리한 이유: 이 경로는 외부 I/O(Expo, SMTP, ES)를 하고, 그 지연이 평가
 * 스레드를 잡으면 틱 파이프라인까지 밀린다. 평가는 Kafka notify.commands에 넣고 끝내고,
 * 이 클래스는 NotifyKafkaConsumer가 별도 스레드(또는 별도 role=notify 프로세스)에서 호출한다.
 * 코드는 이전 AlertEvaluator.dispatchAlert에서 그대로 옮겼다 — SQL·순서·쿨다운 키 동일.
 */
@Component
class AlertDispatcher(
    private val jdbc: JdbcTemplate,
    private val pushSender: ExpoPushSender,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val redis: StringRedisTemplate,
    private val mailSender: JavaMailSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatch(rule: AlertRuleRow, currentPrice: BigDecimal) {
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

        log.info("[AlertDispatcher] triggered: ruleId={} userId={} price={}", rule.id, rule.userId, currentPrice)

        val tokens = jdbc.queryForList(
            "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true",
            String::class.java,
            rule.userId,
        )
        if (tokens.isEmpty()) {
            sendEmailFallback(rule.userId, message)
            finish(historyId, rule, message, "EMAIL_FALLBACK")
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
        finish(historyId, rule, message, status)
        log.info("[AlertDispatcher] push sent: userId={} status={}", rule.userId, status)
    }

    /**
     * ADR-042: 최종 상태 UPDATE와 색인 이벤트를 한 트랜잭션에. 이전엔 ES를 직접 쓰고 실패를 WARN으로 삼켰고,
     * EMAIL_FALLBACK 경로는 아예 색인하지 않았다. 문서 형태는 api `AlertHistoryDocument`(triggeredAt: epoch_millis).
     */
    private fun finish(historyId: Long, rule: AlertRuleRow, message: String, status: String) {
        tx.execute {
            jdbc.update("UPDATE alert_histories SET delivery_status = ? WHERE id = ?", status, historyId)
            events.publishEvent(SearchIndexEvent.index(SEARCH_INDEX, historyId.toString(), mapOf(
                "ruleId"         to rule.id,
                "userId"         to rule.userId,
                "stockId"        to rule.stockId.takeIf { it != 0L },
                "ruleType"       to rule.ruleType,
                "message"        to message,
                "deliveryStatus" to status,
                "triggeredAt"    to Instant.now().toEpochMilli(),
            )))
        }
    }

    companion object { const val SEARCH_INDEX = "alert_histories" }

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
            log.info("[AlertDispatcher] email fallback sent: userId={}", userId)
        } catch (e: Exception) {
            log.warn("[AlertDispatcher] email fallback failed: userId={} {}", userId, e.message)
        }
    }

    private fun buildMessage(rule: AlertRuleRow, price: BigDecimal) = when (rule.ruleType) {
        "PRICE_ABOVE"    -> "가격이 ₩${price.toLong().formatKR()} 이상이 되었습니다"
        "PRICE_BELOW"    -> "가격이 ₩${price.toLong().formatKR()} 이하가 되었습니다"
        "VOLUME_SURGE"   -> "거래량이 평균 대비 급증했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "RSI_BELOW"      -> "RSI가 과매도 구간에 진입했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "RSI_ABOVE"      -> "RSI가 과매수 구간에 진입했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "PRICE_BELOW_MA" -> "가격이 이동평균선 아래로 이탈했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "PRICE_ABOVE_MA" -> "가격이 이동평균선 위로 돌파했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "HOLDING_DROP"   -> "보유 종목이 평단가 대비 큰 폭으로 하락했습니다 (현재가 ₩${price.toLong().formatKR()})"
        else             -> "알림 조건 충족: ${rule.ruleType}"
    }

    private fun Long.formatKR() = "%,d".format(this)
}
