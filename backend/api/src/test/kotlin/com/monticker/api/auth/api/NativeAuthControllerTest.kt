package com.monticker.api.auth.api

import com.monticker.api.common.http.ClientIpResolver
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.application.TokenPair
import com.monticker.api.auth.infrastructure.CustomOAuth2UserService
import com.monticker.api.auth.infrastructure.HttpCookieOAuth2AuthorizationRequestRepository
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.OAuth2SuccessHandler
import com.monticker.api.auth.infrastructure.RefreshTokenCookie
import com.monticker.api.auth.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

// @WebMvcTest 슬라이스는 기본적으로 실제 SecurityConfig를 안 불러온다 — Spring Boot의 기본
// formLogin 시큐리티가 대신 적용돼 /api/auth/**의 permitAll이 적용되지 않고 302로 리다이렉트된다.
// 실제 인가 규칙(및 그걸 검증하는 이 테스트들)이 의미 있으려면 SecurityConfig를 명시적으로 가져와야 한다.
// Filter 빈(RateLimitFilter, IdempotencyFilter)은 둘 다 StringRedisTemplate을 생성자로 받으므로
// 이 빈이 없으면 컨텍스트 로딩 자체가 실패한다.
@WebMvcTest(NativeAuthController::class)
// RedisGuard: RateLimitFilter/IdempotencyFilter(슬라이스에 포함되는 Filter 빈)가 요구한다.
// 목으로 대체하면 failOpen()이 null을 돌려줘 Kotlin 언박싱 NPE가 나므로 실제 빈을 쓴다 —
// 의존인 MeterRegistry는 슬라이스에 없어 SimpleMeterRegistry를 함께 올린다.
@Import(SecurityConfig::class, RedisGuard::class, SimpleMeterRegistry::class, RefreshTokenCookie::class, ClientIpResolver::class)
/** ADR-109 — 네이티브 앱 토큰 경로: refresh token을 본문으로 주고받고, 쿠키는 쓰지 않는다 */
class NativeAuthControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockBean lateinit var authService: AuthService
    @MockBean lateinit var jwtTokenProvider: JwtTokenProvider
    // RateLimitFilter가 redis.opsForValue().increment(...)를 체이닝으로 호출하므로 deep stub 필요
    // (기본 Mockito 목은 opsForValue()가 null을 반환해 NPE가 남)
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @MockBean lateinit var oauth2SuccessHandler: OAuth2SuccessHandler
    @MockBean lateinit var customOAuth2UserService: CustomOAuth2UserService
    @MockBean lateinit var cookieAuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository

    private fun json(vararg pairs: Pair<String, String>) = objectMapper.writeValueAsString(mapOf(*pairs))

    @Test
    fun `로그인하면 access·refresh token을 본문으로 주고 쿠키는 굽지 않는다`() {
        given(authService.login("u@test.com", "password123")).willReturn(TokenPair("access", "refresh"))
        given(jwtTokenProvider.refreshTokenExpiryMs()).willReturn(60_000L)

        mvc.post("/api/auth/native/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to "u@test.com", "password" to "password123")
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("access") }
            jsonPath("$.refreshToken") { value("refresh") }
            jsonPath("$.refreshExpiresAt") { exists() }
            header { doesNotExist("Set-Cookie") }
        }
    }

    @Test
    fun `비밀번호가 틀리면 401 — 이유는 알려 주지 않는다`() {
        given(authService.login(anyString(), anyString())).willThrow(IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."))

        mvc.post("/api/auth/native/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to "u@test.com", "password" to "wrong-pass1")
        }.andExpect {
            status { isUnauthorized() }
            content { string("") }
        }
    }

    @Test
    fun `본문의 refresh token으로 회전한다 — 새 토큰 쌍을 준다`() {
        given(authService.refresh("old-refresh")).willReturn(TokenPair("access2", "refresh2"))
        given(jwtTokenProvider.refreshTokenExpiryMs()).willReturn(60_000L)

        mvc.post("/api/auth/native/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json("refreshToken" to "old-refresh")
        }.andExpect {
            status { isOk() }
            jsonPath("$.refreshToken") { value("refresh2") }
        }
    }

    @Test
    fun `폐기됐거나 위조된 refresh token은 401, 비었으면 400`() {
        given(authService.refresh("reused")).willThrow(IllegalArgumentException("만료되었거나 유효하지 않은 refresh token입니다."))

        mvc.post("/api/auth/native/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json("refreshToken" to "reused")
        }.andExpect { status { isUnauthorized() } }

        mvc.post("/api/auth/native/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json("refreshToken" to " ")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `웹 refresh 쿠키만 가지고는 네이티브 갱신을 할 수 없다 — 본문 토큰이 필요하다`() {
        mvc.post("/api/auth/native/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
            cookie(jakarta.servlet.http.Cookie("refreshToken", "web-cookie-token"))
        }.andExpect { status { isBadRequest() } }
        org.mockito.Mockito.verifyNoInteractions(authService)
    }

    @Test
    fun `로그아웃은 본문의 그 토큰만 폐기한다`() {
        mvc.post("/api/auth/native/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = json("refreshToken" to "device-refresh")
        }.andExpect { status { isNoContent() } }
        org.mockito.Mockito.verify(authService).logout("device-refresh")
    }
}
