package com.monticker.api.auth.api

import com.monticker.api.auth.application.NotificationPreferenceService
import com.monticker.api.auth.config.SecurityConfig
import com.monticker.api.auth.infrastructure.CustomOAuth2UserService
import com.monticker.api.auth.infrastructure.HttpCookieOAuth2AuthorizationRequestRepository
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.OAuth2SuccessHandler
import com.monticker.api.auth.infrastructure.RefreshTokenCookie
import com.monticker.api.common.http.ClientIpResolver
import com.monticker.api.common.notification.UnsubscribeScope
import com.monticker.api.common.notification.UnsubscribeTokenService
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * ADR-102 — 원클릭 수신 거부 엔드포인트. 실제 SecurityConfig 필터 체인으로 "로그인 없이 POST만"을 확인한다.
 * 토큰 서명은 진짜 [UnsubscribeTokenService](application.yml 개발 기본값)로 만든다.
 */
@WebMvcTest(UnsubscribeController::class)
@Import(
    SecurityConfig::class, RedisGuard::class, SimpleMeterRegistry::class, RefreshTokenCookie::class, ClientIpResolver::class,
    UnsubscribeTokenService::class,
)
class UnsubscribeControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: UnsubscribeTokenService
    @MockBean lateinit var preferences: NotificationPreferenceService
    @MockBean lateinit var jwtTokenProvider: JwtTokenProvider
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @MockBean lateinit var oauth2SuccessHandler: OAuth2SuccessHandler
    @MockBean lateinit var customOAuth2UserService: CustomOAuth2UserService
    @MockBean lateinit var cookieAuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository

    private fun oneClick(token: String?) = mvc.post("/api/unsubscribe") {
        if (token != null) param("token", token)
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        content = "List-Unsubscribe=One-Click"
    }

    @Test
    fun `RFC 8058 one-click POST without login turns the weekly report off`() {
        val token = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        given(preferences.disableWeeklyReportEmail(42)).willReturn(true)

        val body = oneClick(token).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("unsubscribed") }
            header { string("Cache-Control", "no-store") }
        }.andReturn().response.contentAsString

        verify(preferences).disableWeeklyReportEmail(42)
        assertThat(body).doesNotContain("42").doesNotContain("@")
    }

    @Test
    fun `token in the query string is enough — the form body is not required`() {
        val token = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        mvc.post("/api/unsubscribe?token=$token").andExpect { status { isOk() } }
        verify(preferences).disableWeeklyReportEmail(42)
    }

    @Test
    fun `repeating the request is harmless and answers the same`() {
        val token = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        given(preferences.disableWeeklyReportEmail(42)).willReturn(true, false)
        repeat(2) { oneClick(token).andExpect { status { isOk() }; jsonPath("$.status") { value("unsubscribed") } } }
        verify(preferences, times(2)).disableWeeklyReportEmail(42)
    }

    @Test
    fun `deleted or unknown user with a validly signed token gets the same success response`() {
        val token = tokens.issue(99, UnsubscribeScope.WEEKLY_REPORT)
        given(preferences.disableWeeklyReportEmail(99)).willReturn(false) // 탈퇴 — 아무것도 안 함
        oneClick(token).andExpect { status { isOk() }; jsonPath("$.status") { value("unsubscribed") } }
    }

    @Test
    fun `GET never unsubscribes — mail scanners prefetch links`() {
        val token = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        mvc.get("/api/unsubscribe?token=$token").andExpect { status { isMethodNotAllowed() } }
        verify(preferences, never()).disableWeeklyReportEmail(anyLong())
    }

    @Test
    fun `invalid, tampered and missing tokens get one indistinguishable 400 without touching the DB`() {
        val good = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        val shapes = listOf(null, "", "garbage", good.replace(".42.", ".43."), good.dropLast(1) + "x").map { t ->
            oneClick(t).andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value(UnsubscribeController.INVALID_MESSAGE) }
                jsonPath("$.detail") { doesNotExist() }
            }.andReturn().response.contentAsString.replace(Regex("\"timestamp\":\"[^\"]*\""), "")
        }
        assertThat(shapes.toSet()).hasSize(1)
        verify(preferences, never()).disableWeeklyReportEmail(anyLong())
    }

    @Test
    fun `a bogus Authorization header does not block the public endpoint`() {
        val token = tokens.issue(42, UnsubscribeScope.WEEKLY_REPORT)
        mvc.post("/api/unsubscribe?token=$token") { header("Authorization", "Bearer not-a-jwt") }
            .andExpect { status { isOk() } }
    }
}
