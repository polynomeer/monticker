package com.monticker.worker.notification

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalTime

/**
 * ADR-082 — 발송 직전에 사용자 알림 설정과 마케팅 동의를 읽는다. 캐시하지 않는다: 알림은 사건당 한 번이라 조회 비용이 작고, 끈 직후의
 * 알림이 나가면 안 된다(특히 광고성).
 *
 * 행이 없으면 api가 예전에 쓰던 Redis `notif:pref:{userId}`를 읽는다(지연 이전 — api가 저장하면 행이 생기고 키는 지워진다). 그것도
 * 없으면 기본값. **읽기 실패는 기본값이 아니라 예외**다: 기본값(대부분 켜짐)으로 읽으면 끈 사람에게 보내게 된다. 호출자가 재시도한다.
 */
@Component
class NotificationPreferenceReader(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun forUser(userId: Long): NotificationPreference = forUsers(listOf(userId)).getValue(userId)

    fun forUsers(userIds: Collection<Long>): Map<Long, NotificationPreference> {
        if (userIds.isEmpty()) return emptyMap()
        val ids = userIds.distinct()
        val rows = jdbc.query(
            "SELECT * FROM notification_preferences WHERE user_id IN (${ids.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("user_id") to rs.toPreference() },
            *ids.toTypedArray(),
        ).toMap()
        return ids.associateWith { id -> rows[id] ?: legacy(id) ?: NotificationPreference() }
    }

    /** 마케팅(광고성 정보) 수신 동의의 최신 상태. 동의 원장은 추가 전용이라 최신 행이 현재 상태다(ADR-068). */
    fun marketingAgreed(userId: Long): Boolean =
        jdbc.queryForList(
            """SELECT agreed FROM user_consents WHERE user_id = ? AND consent_type = 'MARKETING'
               ORDER BY recorded_at DESC, id DESC LIMIT 1""",
            Boolean::class.java, userId,
        ).firstOrNull() ?: false

    private fun legacy(userId: Long): NotificationPreference? {
        val json = redis.opsForValue().get("notif:pref:$userId") ?: return null
        return runCatching { mapper.readValue(json, NotificationPreference::class.java) }
            .onFailure { log.warn("옛 알림 설정 해석 실패 — 기본값: userId={}", userId) }
            .getOrNull()
    }

    private fun ResultSet.toPreference() = NotificationPreference(
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
    ).withQuietHours(this)

    /**
     * ADR-093 — 방해 금지 시간(V90). worker가 api보다 먼저 배포되면 V90이 아직 없다(마이그레이션은 api가 한다) — 컬럼이 없으면
     * 꺼짐(기본값)으로 읽는다. 끌 수 없는 알림은 어차피 이 값을 보지 않는다.
     */
    private fun NotificationPreference.withQuietHours(rs: ResultSet): NotificationPreference {
        val md = rs.metaData
        val columns = (1..md.columnCount).map { md.getColumnLabel(it).lowercase() }.toSet()
        if ("quiet_hours_enabled" !in columns) return this
        return copy(
            quietHoursEnabled = rs.getBoolean("quiet_hours_enabled"),
            quietHoursStart = rs.getObject("quiet_hours_start", LocalTime::class.java),
            quietHoursEnd = rs.getObject("quiet_hours_end", LocalTime::class.java),
        )
    }
}
