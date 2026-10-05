package com.monticker.api.auth.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.monticker.api.auth.application.NotificationPreferenceService
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

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

    @PutMapping("/notification-preferences")
    fun update(
        @RequestHeader("Authorization") auth: String,
        @RequestBody body: NotificationPreferenceRequest,
    ): ResponseEntity<NotificationPreferenceRequest> =
        ResponseEntity.ok(preferences.save(userId(auth), body))
}
