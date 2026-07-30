package com.monticker.api.auth.api

import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.application.TokenPair
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 로컬 개발 전용 소셜 로그인 Mock.
 *
 * app.social.mock.enabled=true 일 때만 활성화된다.
 * 실제 OAuth2 provider를 호출하지 않고 socialLoginOrSignup을 직접 실행한다.
 * 프로덕션 배포 시에는 해당 설정을 false로 두거나 제거한다.
 */
@ConditionalOnProperty("app.social.mock.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/auth/mock-social")
class MockSocialLoginController(
    private val authService: AuthService,
) {

    private val allowedProviders = setOf("GOOGLE", "KAKAO", "NAVER")

    @PostMapping
    fun mockLogin(
        @RequestParam provider: String,
        @RequestParam(defaultValue = "") email: String,
        @RequestParam(defaultValue = "") nickname: String,
    ): ResponseEntity<TokenPair> {
        val p = provider.uppercase()
        require(p in allowedProviders) { "지원하지 않는 provider: $provider" }

        val resolvedEmail    = email.ifBlank { "mock_${p.lowercase()}@dev.monticker.io" }
        val resolvedNickname = nickname.ifBlank { "${p}User" }
        val providerId       = "mock_${resolvedEmail}"

        val tokens = authService.socialLoginOrSignup(resolvedEmail, resolvedNickname, p, providerId)
        return ResponseEntity.ok(tokens)
    }
}
