package com.monticker.api.auth.api

import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.application.TokenPair
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * ADR-109 — 네이티브 앱(모바일)용 토큰 경로. 웹은 refresh token을 HttpOnly 쿠키로만 주고받는다(security-review C2, [AuthController]).
 * 네이티브 앱에는 쿠키 저장소를 믿을 수 없으므로(플랫폼마다 다르고 앱 재설치·OS 정리에 사라진다) refresh token을 **본문으로**
 * 주고받고, 앱이 OS 보안 저장소(iOS Keychain·Android Keystore, expo-secure-store)에 둔다.
 *
 * 회전·재사용 탐지·폐기는 웹과 같은 [AuthService]를 그대로 쓴다 — 쓴 refresh token은 바로 폐기되고, 폐기된 토큰을 다시 쓰면
 * 그 사용자의 모든 세션이 끊긴다. 레이트리밋 버킷도 웹 로그인·갱신과 같이 쓴다(RateLimitFilter).
 *
 * 웹 쿠키 방어(C2)를 약하게 하지 않는다: 이 경로는 쿠키를 읽지도 쓰지도 않으므로, 웹 페이지의 스크립트가 이미 가진 쿠키로
 * refresh token을 본문으로 꺼낼 길이 없다. 본문 토큰을 얻으려면 비밀번호가 다시 필요하다.
 */
@Validated
@RestController
@RequestMapping("/api/auth/native")
class NativeAuthController(
    private val authService: AuthService,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ResponseEntity<NativeTokenResponse> =
        try {
            ResponseEntity.ok(authService.login(req.email, req.password).toNative())
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(401).build()
        }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: NativeRefreshRequest): ResponseEntity<NativeTokenResponse> =
        try {
            ResponseEntity.ok(authService.refresh(req.refreshToken).toNative())
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(401).build()
        }

    /** 이 기기의 refresh token만 폐기한다. 이미 없는 토큰이어도 204 — 앱은 결과와 관계없이 저장소를 비운다 */
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody req: NativeRefreshRequest): ResponseEntity<Void> {
        authService.logout(req.refreshToken)
        return ResponseEntity.noContent().build()
    }

    private fun TokenPair.toNative() = NativeTokenResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
        refreshExpiresAt = Instant.now().plusMillis(jwtTokenProvider.refreshTokenExpiryMs()),
    )
}

data class NativeRefreshRequest(@field:NotBlank @field:Size(max = 4096) val refreshToken: String)

data class NativeTokenResponse(val accessToken: String, val refreshToken: String, val refreshExpiresAt: Instant)
