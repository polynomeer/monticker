package com.monticker.api.auth.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.auth.api.NotificationPreferenceRequest
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

/**
 * ADR-082 — 알림 설정의 저장소는 Postgres `notification_preferences`(V78)다. worker 발송 경로가 같은 행을 읽는다.
 *
 * 예전에는 Redis `notif:pref:{userId}`에만 저장했고 아무도 읽지 않았다. 행이 없으면 그 옛 값을 읽어 돌려준다(지연 이전) —
 * 저장하면 행이 생기고 옛 키는 지운다. 옛 값도 없으면 기본값이다.
 */
@Service
class NotificationPreferenceService(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun get(userId: Long): NotificationPreferenceRequest =
        jdbc.query("SELECT * FROM notification_preferences WHERE user_id = ?", { rs, _ -> rs.toPreference() }, userId).firstOrNull()
            ?: legacy(userId)
            ?: NotificationPreferenceRequest()

    @Transactional
    fun save(userId: Long, p: NotificationPreferenceRequest): NotificationPreferenceRequest {
        jdbc.update(
            """
            INSERT INTO notification_preferences (user_id, all_enabled, push_enabled, email_enabled,
                price_alert_push, price_alert_email, volume_surge_push, volume_surge_email, news_alert_push, news_alert_email,
                quant_signal_push, quant_signal_email, fills_push, fills_email,
                strategy_market_news_push, strategy_market_news_email, weekly_report_email, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (user_id) DO UPDATE SET
                all_enabled = EXCLUDED.all_enabled, push_enabled = EXCLUDED.push_enabled, email_enabled = EXCLUDED.email_enabled,
                price_alert_push = EXCLUDED.price_alert_push, price_alert_email = EXCLUDED.price_alert_email,
                volume_surge_push = EXCLUDED.volume_surge_push, volume_surge_email = EXCLUDED.volume_surge_email,
                news_alert_push = EXCLUDED.news_alert_push, news_alert_email = EXCLUDED.news_alert_email,
                quant_signal_push = EXCLUDED.quant_signal_push, quant_signal_email = EXCLUDED.quant_signal_email,
                fills_push = EXCLUDED.fills_push, fills_email = EXCLUDED.fills_email,
                strategy_market_news_push = EXCLUDED.strategy_market_news_push,
                strategy_market_news_email = EXCLUDED.strategy_market_news_email,
                weekly_report_email = EXCLUDED.weekly_report_email, updated_at = now()
            """.trimIndent(),
            userId, p.allEnabled, p.pushEnabled, p.emailEnabled,
            p.priceAlertPush, p.priceAlertEmail, p.volumeSurgePush, p.volumeSurgeEmail, p.newsAlertPush, p.newsAlertEmail,
            p.quantSignalPush, p.quantSignalEmail, p.fillsPush, p.fillsEmail,
            p.strategyMarketNewsPush, p.strategyMarketNewsEmail, p.weeklyReportEmail,
        )
        // 옛 키가 남아 있으면 worker가 행보다 먼저 볼 일은 없지만(행 우선), 이전이 끝났으니 지운다. 실패해도 행이 이긴다.
        runCatching { redis.delete(legacyKey(userId)) }
        return p
    }

    private fun legacy(userId: Long): NotificationPreferenceRequest? =
        runCatching { redis.opsForValue().get(legacyKey(userId)) }.getOrNull()?.let { json ->
            runCatching { objectMapper.readValue(json, NotificationPreferenceRequest::class.java) }
                .onFailure { log.warn("옛 알림 설정 해석 실패 — 기본값: userId={}", userId) }
                .getOrNull()
        }

    private fun ResultSet.toPreference() = NotificationPreferenceRequest(
        allEnabled = getBoolean("all_enabled"),
        pushEnabled = getBoolean("push_enabled"),
        emailEnabled = getBoolean("email_enabled"),
        priceAlertPush = getBoolean("price_alert_push"),
        priceAlertEmail = getBoolean("price_alert_email"),
        volumeSurgePush = getBoolean("volume_surge_push"),
        volumeSurgeEmail = getBoolean("volume_surge_email"),
        newsAlertPush = getBoolean("news_alert_push"),
        newsAlertEmail = getBoolean("news_alert_email"),
        quantSignalPush = getBoolean("quant_signal_push"),
        quantSignalEmail = getBoolean("quant_signal_email"),
        fillsPush = getBoolean("fills_push"),
        fillsEmail = getBoolean("fills_email"),
        strategyMarketNewsPush = getBoolean("strategy_market_news_push"),
        strategyMarketNewsEmail = getBoolean("strategy_market_news_email"),
        weeklyReportEmail = getBoolean("weekly_report_email"),
    )

    companion object {
        /** 옛 저장 위치 — worker `NotificationPreferences`도 같은 키를 지연 이전용으로 읽는다. */
        fun legacyKey(userId: Long) = "notif:pref:$userId"
    }
}
