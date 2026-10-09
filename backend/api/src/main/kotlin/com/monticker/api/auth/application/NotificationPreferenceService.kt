package com.monticker.api.auth.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.auth.api.CategoryChannels
import com.monticker.api.auth.api.NotificationChannelsResponse
import com.monticker.api.auth.api.NotificationPreferenceRequest
import com.monticker.api.auth.api.QuietHoursView
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.consent.ConsentType
import com.monticker.api.common.exception.ExternalServiceUnavailableException
import com.monticker.api.common.notification.NotificationCategory
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalTime

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
    private val consents: ConsentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    internal var clock: Clock = Clock.systemUTC()

    /** 조회 응답은 방해 금지 필드를 항상 채운다(행·옛 값에 없으면 V90 기본값). */
    @Transactional(readOnly = true)
    fun get(userId: Long): NotificationPreferenceRequest = withQuietHoursFilled(
        jdbc.query("SELECT * FROM notification_preferences WHERE user_id = ?", { rs, _ -> rs.toPreference() }, userId).firstOrNull()
            ?: legacy(userId)
            ?: NotificationPreferenceRequest(),
    )

    /**
     * ADR-093 — 방해 금지 필드가 null이면 지금 값을 유지한다(그 필드를 모르는 예전 화면이 저장해도 지우지 않는다).
     * 시각은 "HH:mm"(00:00~23:59)만, 시작 = 끝은 거부한다(V90 CHECK와 같은 규칙 — DB 오류 500 전에 400으로).
     */
    @Transactional
    fun save(userId: Long, request: NotificationPreferenceRequest): NotificationPreferenceRequest {
        val current = if (request.quietHoursEnabled == null || request.quietHoursStart == null || request.quietHoursEnd == null) get(userId) else null
        val p = request.copy(
            quietHoursEnabled = request.quietHoursEnabled ?: current!!.quietHoursEnabled,
            quietHoursStart = request.quietHoursStart ?: current!!.quietHoursStart,
            quietHoursEnd = request.quietHoursEnd ?: current!!.quietHoursEnd,
        )
        val start = parseHhMm(p.quietHoursStart!!)
        val end = parseHhMm(p.quietHoursEnd!!)
        require(start != end) { "방해 금지 시간의 시작과 종료가 같을 수 없습니다" }
        jdbc.update(
            """
            INSERT INTO notification_preferences (user_id, all_enabled, push_enabled, email_enabled,
                price_alert_push, price_alert_email, volume_surge_push, volume_surge_email, news_alert_push, news_alert_email,
                quant_signal_push, quant_signal_email, fills_push, fills_email,
                strategy_market_news_push, strategy_market_news_email, weekly_report_email,
                quiet_hours_enabled, quiet_hours_start, quiet_hours_end, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (user_id) DO UPDATE SET
                all_enabled = EXCLUDED.all_enabled, push_enabled = EXCLUDED.push_enabled, email_enabled = EXCLUDED.email_enabled,
                price_alert_push = EXCLUDED.price_alert_push, price_alert_email = EXCLUDED.price_alert_email,
                volume_surge_push = EXCLUDED.volume_surge_push, volume_surge_email = EXCLUDED.volume_surge_email,
                news_alert_push = EXCLUDED.news_alert_push, news_alert_email = EXCLUDED.news_alert_email,
                quant_signal_push = EXCLUDED.quant_signal_push, quant_signal_email = EXCLUDED.quant_signal_email,
                fills_push = EXCLUDED.fills_push, fills_email = EXCLUDED.fills_email,
                strategy_market_news_push = EXCLUDED.strategy_market_news_push,
                strategy_market_news_email = EXCLUDED.strategy_market_news_email,
                weekly_report_email = EXCLUDED.weekly_report_email,
                quiet_hours_enabled = EXCLUDED.quiet_hours_enabled, quiet_hours_start = EXCLUDED.quiet_hours_start,
                quiet_hours_end = EXCLUDED.quiet_hours_end, updated_at = now()
            """.trimIndent(),
            userId, p.allEnabled, p.pushEnabled, p.emailEnabled,
            p.priceAlertPush, p.priceAlertEmail, p.volumeSurgePush, p.volumeSurgeEmail, p.newsAlertPush, p.newsAlertEmail,
            p.quantSignalPush, p.quantSignalEmail, p.fillsPush, p.fillsEmail,
            p.strategyMarketNewsPush, p.strategyMarketNewsEmail, p.weeklyReportEmail,
            p.quietHoursEnabled, start, end,
        )
        // 옛 키가 남아 있으면 worker가 행보다 먼저 볼 일은 없지만(행 우선), 이전이 끝났으니 지운다. 실패해도 행이 이긴다.
        runCatching { redis.delete(legacyKey(userId)) }
        return p
    }

    /**
     * ADR-102 — 이메일 원클릭 수신 거부. 주간 리포트 이메일만 끈다(다른 설정은 그대로). 여러 번 불러도 결과가 같다.
     *
     * - 탈퇴했거나 없는 사용자면 아무것도 하지 않는다(호출자는 성공과 같은 응답을 준다 — 존재 여부를 드러내지 않는다).
     * - 행이 있으면 그 컬럼만 원자적으로 바꾼다(설정 화면 저장과 경합해도 다른 필드를 덮지 않는다).
     * - 행이 없으면 옛 Redis 설정(ADR-082 지연 이전)을 바탕으로 행을 만든다. 이때 Redis를 읽지 못하면 기본값(전부 켜짐)으로
     *   덮어 사용자가 꺼 둔 다른 알림을 되살리는 대신 503으로 실패한다 — 메일 클라이언트·사용자가 다시 시도한다.
     *
     * @return 이번 호출로 꺼졌으면 true(이미 꺼져 있었거나 대상이 아니면 false) — 지표용
     */
    @Transactional
    fun disableWeeklyReportEmail(userId: Long): Boolean {
        val active = jdbc.query("SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL", { _, _ -> 1 }, userId).isNotEmpty()
        if (!active) return false
        val turnedOff = jdbc.update(
            "UPDATE notification_preferences SET weekly_report_email = false, updated_at = now() WHERE user_id = ? AND weekly_report_email",
            userId,
        )
        if (turnedOff > 0) return true
        val hasRow = jdbc.query("SELECT 1 FROM notification_preferences WHERE user_id = ?", { _, _ -> 1 }, userId).isNotEmpty()
        if (hasRow) return false // 이미 꺼져 있다
        val legacyJson = try {
            redis.opsForValue().get(legacyKey(userId))
        } catch (e: Exception) {
            throw ExternalServiceUnavailableException("redis", "잠시 후 다시 시도해주세요", e)
        }
        val base = legacyJson?.let { json ->
            runCatching { objectMapper.readValue(json, NotificationPreferenceRequest::class.java) }
                .onFailure { log.warn("옛 알림 설정 해석 실패 — 기본값: userId={}", userId) }
                .getOrNull()
        } ?: NotificationPreferenceRequest()
        save(userId, base.copy(weeklyReportEmail = false))
        return base.weeklyReportEmail
    }

    /**
     * ADR-093 — 종류별 실제 전달 채널. worker 발송 정책과 같은 규칙([NotificationDeliveryPolicy], 공유 사례표로 테스트)으로 계산한다.
     * 광고성 동의는 발행 전 확인과 같은 기준(현재 문서 버전의 동의)이다.
     */
    @Transactional(readOnly = true)
    fun channels(userId: Long): NotificationChannelsResponse {
        val pref = get(userId)
        val marketing = consents.isAgreed(userId, ConsentType.MARKETING)
        val quiet = pref.quietHours
        return NotificationChannelsResponse(
            quietHours = QuietHoursView(
                enabled = quiet.enabled,
                start = pref.quietHoursStart!!,
                end = pref.quietHoursEnd!!,
                activeNow = NotificationDeliveryPolicy.inQuietHours(pref, clock.instant()),
            ),
            marketingAgreed = marketing,
            categories = NotificationCategory.entries.map { category ->
                val normal = NotificationDeliveryPolicy.plan(pref, category, marketing, inQuietHours = false)
                val quietPlan = NotificationDeliveryPolicy.plan(pref, category, marketing, inQuietHours = true)
                CategoryChannels(
                    category = category.name,
                    alwaysOn = category.alwaysOn,
                    push = normal.push,
                    email = normal.email,
                    emailFallback = normal.emailIfPushMissed,
                    inApp = category.inApp,
                    pushDuringQuietHours = quietPlan.push,
                )
            },
        )
    }

    private fun withQuietHoursFilled(p: NotificationPreferenceRequest): NotificationPreferenceRequest {
        val q = p.quietHours
        return p.copy(quietHoursEnabled = q.enabled, quietHoursStart = hhMm(q.start), quietHoursEnd = hhMm(q.end))
    }

    private fun hhMm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun parseHhMm(value: String): LocalTime {
        require(HH_MM.matches(value)) { "방해 금지 시간은 HH:mm 형식이어야 합니다" }
        return LocalTime.parse(value)
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
        quietHoursEnabled = getBoolean("quiet_hours_enabled"),
        quietHoursStart = hhMm(getObject("quiet_hours_start", LocalTime::class.java)),
        quietHoursEnd = hhMm(getObject("quiet_hours_end", LocalTime::class.java)),
    )

    companion object {
        private val HH_MM = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

        /** 옛 저장 위치 — worker `NotificationPreferences`도 같은 키를 지연 이전용으로 읽는다. */
        fun legacyKey(userId: Long) = "notif:pref:$userId"
    }
}
