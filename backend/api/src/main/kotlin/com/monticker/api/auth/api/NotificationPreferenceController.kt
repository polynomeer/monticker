package com.monticker.api.auth.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.monticker.api.auth.application.NotificationPreferenceService
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.LocalTime

/**
 * ADR-082 — 알림 설정. 기본값은 V78 `notification_preferences` 컬럼 DEFAULT와 같아야 한다(행이 없으면 둘 다 "기본").
 * '결과 확인 중' 주문·조건부 주문 실패 알림은 끌 수 없어 필드가 없다(ADR-056/065).
 * 모르는 필드는 무시한다 — 옛 Redis 값(필드가 적다)과 새 화면이 서로 다른 필드를 보내도 깨지지 않게.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NotificationPreferenceRequest(
    /** 전체 알림 — 끄면 끌 수 있는 알림 전부 중지 */
    val allEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    val emailEnabled: Boolean = true,
    val priceAlertPush: Boolean = true,
    val priceAlertEmail: Boolean = false,
    val volumeSurgePush: Boolean = true,
    val volumeSurgeEmail: Boolean = false,
    val newsAlertPush: Boolean = true,
    val newsAlertEmail: Boolean = false,
    val quantSignalPush: Boolean = true,
    val quantSignalEmail: Boolean = false,
    val fillsPush: Boolean = true,
    val fillsEmail: Boolean = false,
    /** 광고성 — MARKETING 동의(ADR-068)도 있어야 발송된다 */
    val strategyMarketNewsPush: Boolean = false,
    val strategyMarketNewsEmail: Boolean = false,
    val weeklyReportEmail: Boolean = true,
    /**
     * ADR-093 — 방해 금지 시간(KST "HH:mm", 자정을 넘을 수 있다). 켜면 끌 수 있는 알림의 푸시를 그 시간에 보내지 않는다.
     * 저장 요청에서 null이면 **지금 값을 유지한다** — 이 필드를 모르는 예전 화면이 저장해도 방해 금지 설정을 지우지 않게. 조회 응답은 항상 채운다.
     */
    val quietHoursEnabled: Boolean? = null,
    val quietHoursStart: String? = null,
    val quietHoursEnd: String? = null,
) {
    /** null(모름)을 V90 기본값으로 — 정책 계산용 */
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val quietHours: QuietHours
        get() = QuietHours(
            enabled = quietHoursEnabled ?: false,
            start = quietHoursStart?.let(LocalTime::parse) ?: QuietHours.DEFAULT_START,
            end = quietHoursEnd?.let(LocalTime::parse) ?: QuietHours.DEFAULT_END,
        )
}

/** ADR-093 — 방해 금지 구간. 시작 포함·끝 제외, 시작 > 끝이면 자정을 넘는다. 시작 = 끝은 구간 없음(저장은 거부한다). */
data class QuietHours(val enabled: Boolean, val start: LocalTime, val end: LocalTime) {
    fun contains(kstTime: LocalTime): Boolean {
        if (!enabled || start == end) return false
        return if (start < end) kstTime >= start && kstTime < end else kstTime >= start || kstTime < end
    }

    companion object {
        val DEFAULT_START: LocalTime = LocalTime.of(22, 0)
        val DEFAULT_END: LocalTime = LocalTime.of(7, 0)
    }
}

/** ADR-093 — 내 알림이 종류별로 어느 채널로 가는지(worker 발송 정책과 같은 규칙으로 계산). */
data class NotificationChannelsResponse(
    val quietHours: QuietHoursView,
    /** 광고성(전략 마켓 소식)은 이 동의가 있어야 간다 */
    val marketingAgreed: Boolean,
    /** 카카오 알림톡 — 외부 연동 전이라 항상 false */
    val kakaoAvailable: Boolean = false,
    val categories: List<CategoryChannels>,
)

data class QuietHoursView(val enabled: Boolean, val start: String, val end: String, val activeNow: Boolean)

data class CategoryChannels(
    val category: String,
    /** 끌 수 없음 — 설정·방해 금지 시간과 무관하게 즉시 푸시(닿지 않으면 이메일) */
    val alwaysOn: Boolean,
    val push: Boolean,
    val email: Boolean,
    /** 푸시가 어느 기기에도 닿지 않으면 이메일로 대신 보낸다 */
    val emailFallback: Boolean,
    /** 알림 이력(/alerts)에 남는다 — 설정과 무관 */
    val inApp: Boolean,
    /** 방해 금지 시간에도 푸시한다(끌 수 없는 종류만) */
    val pushDuringQuietHours: Boolean,
)

@Validated
@RestController
@RequestMapping("/api/users/me")
class NotificationPreferenceController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val preferences: NotificationPreferenceService,
) {
    private fun userId(auth: String) = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())

    @GetMapping("/notification-preferences")
    fun get(@RequestHeader("Authorization") auth: String): ResponseEntity<NotificationPreferenceRequest> =
        ResponseEntity.ok(preferences.get(userId(auth)))

    /** ADR-093 — 종류별 실제 전달 채널(푸시·이메일·이력), 방해 금지 시간 상태. 알림 화면의 "전달 채널" 패널용. */
    @GetMapping("/notification-preferences/channels")
    fun channels(@RequestHeader("Authorization") auth: String): ResponseEntity<NotificationChannelsResponse> =
        ResponseEntity.ok(preferences.channels(userId(auth)))

    @PutMapping("/notification-preferences")
    fun update(
        @RequestHeader("Authorization") auth: String,
        @RequestBody body: NotificationPreferenceRequest,
    ): ResponseEntity<NotificationPreferenceRequest> =
        ResponseEntity.ok(preferences.save(userId(auth), body))
}
