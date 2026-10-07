package com.monticker.api.brokerage.api

import com.monticker.api.common.http.ClientIpResolver
import com.monticker.api.auth.config.SecurityConfig
import com.monticker.api.auth.infrastructure.CustomOAuth2UserService
import com.monticker.api.auth.infrastructure.HttpCookieOAuth2AuthorizationRequestRepository
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.OAuth2SuccessHandler
import com.monticker.api.auth.infrastructure.RefreshTokenCookie
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
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

/** ADR-056 Note — 결과 불명 주문 수동 확정은 관리자만. 일반 사용자가 남의 주문을 "체결됨"으로 바꿀 수 있으면 안 된다. */
@WebMvcTest(BrokerageOrderAdminController::class)
@Import(SecurityConfig::class, RedisGuard::class, SimpleMeterRegistry::class, RefreshTokenCookie::class, ClientIpResolver::class)
class BrokerageOrderAdminSecurityTest {

    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var brokerageService: BrokerageService
    @MockBean lateinit var jwtTokenProvider: JwtTokenProvider
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @MockBean lateinit var oauth2SuccessHandler: OAuth2SuccessHandler
    @MockBean lateinit var customOAuth2UserService: CustomOAuth2UserService
    @MockBean lateinit var cookieAuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository

    @Test
    fun `일반 사용자는 결과 불명 주문을 확정할 수 없다 — 403`() {
        mvc.post("/api/admin/brokerage-orders/1/resolve") {
            with(user("1").roles("USER")); contentType = MediaType.APPLICATION_JSON; content = """{"notPlaced":true,"note":"x"}"""
        }.andExpect { status { isForbidden() } }
        verify(brokerageService, never()).resolveManually(anyLong(), any(), any(), anyBoolean(), anyString())
    }

    @Test
    fun `일반 사용자는 목록도 볼 수 없다 — 403`() {
        mvc.get("/api/admin/brokerage-orders/unresolved") { with(user("1").roles("USER")) }.andExpect { status { isForbidden() } }
    }
}
