package com.monticker.api.auth.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.infrastructure.CustomOAuth2UserService
import com.monticker.api.auth.infrastructure.HttpCookieOAuth2AuthorizationRequestRepository
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.OAuth2SuccessHandler
import com.monticker.api.common.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
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
@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class)
class AuthControllerTest {

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

    @Test
    fun `회원가입 - 유효하지 않은 이메일은 400`() {
        mvc.post("/api/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to "not-an-email", "password" to "password123", "nickname" to "tester")
            )
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `회원가입 - 비밀번호 8자 미만이면 400`() {
        mvc.post("/api/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to "user@test.com", "password" to "short", "nickname" to "tester")
            )
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    @WithMockUser
    fun `회원가입 - 정상 요청은 200`() {
        given(authService.signup(anyString(), anyString(), anyString())).willReturn(
            com.monticker.api.auth.application.TokenPair("access", "refresh")
        )
        mvc.post("/api/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to "user@test.com", "password" to "password123", "nickname" to "tester")
            )
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("access") }
        }
    }

    @Test
    fun `로그인 - 빈 이메일은 400`() {
        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to "", "password" to "password123")
            )
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
