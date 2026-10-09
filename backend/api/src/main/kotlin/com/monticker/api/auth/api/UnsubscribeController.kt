package com.monticker.api.auth.api

import com.monticker.api.auth.application.NotificationPreferenceService
import com.monticker.api.common.exception.ErrorResponse
import com.monticker.api.common.notification.UnsubscribeScope
import com.monticker.api.common.notification.UnsubscribeTokenService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class UnsubscribeResponse(val status: String = "unsubscribed")

/**
 * ADR-102 — 이메일 원클릭 수신 거부(RFC 8058). **로그인 없이** 서명 토큰만으로 주간 리포트 이메일을 끈다.
 *
 * - `POST /api/unsubscribe?token=…` — 메일 클라이언트(Gmail·Apple Mail 등)가 `List-Unsubscribe-Post: List-Unsubscribe=One-Click`
 *   헤더를 보고 본문 `List-Unsubscribe=One-Click`(form)으로 보낸다. 웹 확인 화면(`/unsubscribe`)도 같은 요청을 보낸다.
 *   본문은 요구하지 않는다 — 판단은 토큰 서명만으로 한다.
 * - **GET은 없다**(405). 메일 보안 스캐너가 링크를 미리 열어 보는 것만으로 수신 거부되면 안 된다(RFC 8058 §1).
 * - 응답에 사용자 정보를 담지 않는다. 서명이 틀린 토큰은 DB를 보기 전에 같은 400으로 거절한다 — 사용자 존재 여부와 무관한
 *   응답이다. 서명이 맞는데 탈퇴했거나 없는 사용자면 성공과 같은 200(아무것도 안 함)이다.
 * - 레이트리밋은 `/api` 하위 공통 IP 버킷(RateLimitFilter)을 그대로 쓴다. 메일 사업자는 공용 IP 몇 개에서 대량으로 보내므로
 *   더 좁은 전용 버킷을 두지 않는다 — 서명 검증이 DB 접근보다 먼저라 무차별 대입으로 얻을 것도 없다.
 */
@RestController
@RequestMapping("/api/unsubscribe")
class UnsubscribeController(
    private val tokens: UnsubscribeTokenService,
    private val preferences: NotificationPreferenceService,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun unsubscribe(@RequestParam(required = false) token: String?): ResponseEntity<Any> {
        val userId = tokens.verify(token, UnsubscribeScope.WEEKLY_REPORT)
        if (userId == null) {
            registry.counter("email_unsubscribe_total", "scope", UnsubscribeScope.WEEKLY_REPORT.code, "result", "invalid").increment()
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(ErrorResponse(HttpStatus.BAD_REQUEST.value(), INVALID_MESSAGE))
        }
        val changed = preferences.disableWeeklyReportEmail(userId)
        registry.counter("email_unsubscribe_total", "scope", UnsubscribeScope.WEEKLY_REPORT.code, "result", if (changed) "changed" else "noop")
            .increment()
        if (changed) log.info("[Unsubscribe] weekly_report off userId={}", userId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(UnsubscribeResponse())
    }

    companion object {
        const val INVALID_MESSAGE = "유효하지 않은 수신 거부 링크입니다"
    }
}
