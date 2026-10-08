package com.monticker.api.auth.api

import com.monticker.api.auth.application.UserPreferenceService
import com.monticker.api.auth.application.UserPreferences
import com.monticker.api.common.aop.RateLimited
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * 본문은 문자열로 받고 서비스에서 화이트리스트로 바꾼다 — enum으로 바로 받으면 모르는 값이 Jackson 파싱 오류
 * ("요청 본문을 파싱할 수 없습니다")가 되어 어떤 필드가 틀렸는지 알려 줄 수 없다.
 */
data class UserPreferenceRequest(
    val interestSectors: List<String?>? = emptyList(),
    val usageStyle: String? = null,
)

/** ADR-099 — "관심 분야 순" 스위치만 바꾼다. 빠지면 400(무엇을 바꾸려는지 모르는 요청). */
data class UserPreferencePatchRequest(val interestOrdering: Boolean? = null)

/** ADR-089 — 온보딩 관심 분야·사용 방식. 대상 사용자는 인증 토큰으로만 정한다. */
@RestController
@RequestMapping("/api/users/me/preferences")
class UserPreferenceController(private val preferences: UserPreferenceService) {

    @GetMapping
    fun get(@AuthenticationPrincipal userId: Long): ResponseEntity<UserPreferences> =
        ResponseEntity.ok(preferences.get(userId))

    @PutMapping
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "users.preferences")
    fun put(@AuthenticationPrincipal userId: Long, @RequestBody body: UserPreferenceRequest): ResponseEntity<UserPreferences> {
        val (sectors, style) = UserPreferenceService.parse(body.interestSectors, body.usageStyle)
        return ResponseEntity.ok(preferences.save(userId, sectors, style))
    }

    @PatchMapping
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "users.preferences")
    fun patch(@AuthenticationPrincipal userId: Long, @RequestBody body: UserPreferencePatchRequest): ResponseEntity<UserPreferences> {
        val enabled = requireNotNull(body.interestOrdering) { "interestOrdering(true/false)이 필요합니다" }
        return ResponseEntity.ok(preferences.setInterestOrdering(userId, enabled))
    }
}
