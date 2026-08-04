package com.monticker.api.auth.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(AuthController::class)
class AuthControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockBean lateinit var authService: AuthService
    @MockBean lateinit var jwtTokenProvider: JwtTokenProvider

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
