package com.monticker.api.auth.infrastructure

import com.monticker.api.auth.application.AuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

/**
 * 소셜 로그인(카카오·구글) 성공 시 JWT를 발급하고 웹 앱으로 리다이렉트한다.
 *
 * 아직 OAuth2 provider가 설정되지 않아 활성화되지 않음.
 * SecurityConfig에 .oauth2Login { it.successHandler(this) }를 추가하면 동작한다.
 */
@Component
class OAuth2SuccessHandler(
    private val authService: AuthService,
    private val jwtTokenProvider: JwtTokenProvider,
    @Value("\${app.base-url:http://localhost:3000}") private val baseUrl: String,
) : AuthenticationSuccessHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val oauth2User = authentication.principal as OAuth2User
        val email      = oauth2User.getAttribute<String>("email") ?: run {
            log.warn("[OAuth2] email attribute missing in principal")
            response.sendRedirect("$baseUrl/login?error=oauth2")
            return
        }
        val nickname   = oauth2User.getAttribute<String>("name") ?: email.substringBefore("@")

        val tokens = authService.socialLoginOrSignup(email, nickname)
        log.info("[OAuth2] social login success: email={}", email)
        response.sendRedirect("$baseUrl/oauth2/callback?accessToken=${tokens.accessToken}&refreshToken=${tokens.refreshToken}")
    }
}
