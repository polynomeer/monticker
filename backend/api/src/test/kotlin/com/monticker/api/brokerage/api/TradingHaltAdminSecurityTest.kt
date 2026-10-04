package com.monticker.api.brokerage.api

import com.monticker.api.auth.config.SecurityConfig
import com.monticker.api.auth.infrastructure.CustomOAuth2UserService
import com.monticker.api.auth.infrastructure.HttpCookieOAuth2AuthorizationRequestRepository
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.OAuth2SuccessHandler
import com.monticker.api.auth.infrastructure.RefreshTokenCookie
import com.monticker.api.brokerage.application.HaltScope
import com.monticker.api.brokerage.application.TradingHalt
import com.monticker.api.brokerage.application.TradingHaltService
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

/**
 * ADR-057 — 킬 스위치는 관리자만 켜고 끈다. 실제 SecurityConfig 필터 체인으로 확인한다.
 *
 * 이 테스트가 생기기 전에는 `@PreAuthorize("hasRole('ADMIN')")`가 @EnableMethodSecurity 없이 붙어 있어 아무 효과가
 * 없었다 — 로그인한 일반 사용자가 사고 대응 중인 스위치를 끌 수 있었다(같은 구멍이 /api/admin/batch·search에도 있었다).
 */
@WebMvcTest(TradingHaltAdminController::class)
@Import(SecurityConfig::class, RedisGuard::class, SimpleMeterRegistry::class, RefreshTokenCookie::class)
class TradingHaltAdminSecurityTest {

    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var tradingHaltService: TradingHaltService
    @MockBean lateinit var jwtTokenProvider: JwtTokenProvider
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @MockBean lateinit var oauth2SuccessHandler: OAuth2SuccessHandler
    @MockBean lateinit var customOAuth2UserService: CustomOAuth2UserService
    @MockBean lateinit var cookieAuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository

    // Mockito any()는 null을 돌려줘 Kotlin non-null 파라미터에서 NPE가 난다 — 기본값으로 채운다.
    private fun anyScope(): HaltScope = any(HaltScope::class.java) ?: HaltScope.GLOBAL

    private val haltBody = """{"scope":"GLOBAL","reason":"사고 대응"}"""

    @Test
    fun `일반 사용자는 스위치를 켤 수 없다 — 403`() {
        mvc.post("/api/admin/trading-halts") {
            with(user("1").roles("USER")); contentType = MediaType.APPLICATION_JSON; content = haltBody
        }.andExpect { status { isForbidden() } }
        verify(tradingHaltService, never()).halt(anyScope(), any(), anyString(), any())
    }

    @Test
    fun `일반 사용자는 스위치를 끌 수 없다 — 403`() {
        mvc.post("/api/admin/trading-halts/1/lift") {
            with(user("1").roles("USER")); contentType = MediaType.APPLICATION_JSON; content = """{"reason":"x"}"""
        }.andExpect { status { isForbidden() } }
        verify(tradingHaltService, never()).lift(anyLong(), anyString(), any())
    }

    @Test
    fun `인증 없이는 401`() {
        mvc.get("/api/admin/trading-halts").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `관리자는 켤 수 있다 — 201`() {
        given(tradingHaltService.halt(anyScope(), any(), anyString(), any())).willReturn(
            TradingHalt(1, HaltScope.GLOBAL, null, "사고 대응", null, Instant.now(), null, null, null),
        )
        mvc.post("/api/admin/trading-halts") {
            with(user("9").roles("ADMIN")); contentType = MediaType.APPLICATION_JSON; content = haltBody
        }.andExpect { status { isCreated() }; jsonPath("$.active") { value(true) } }
    }
}
