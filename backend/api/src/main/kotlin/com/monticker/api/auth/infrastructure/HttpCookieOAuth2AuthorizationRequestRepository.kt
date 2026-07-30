package com.monticker.api.auth.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.stereotype.Component
import java.util.Base64

private const val COOKIE_NAME = "oauth2_auth_request"
private const val COOKIE_MAX_AGE = 180

/**
 * OAuth2 인증 요청을 HTTP 세션 대신 쿠키에 저장해 서버 측 세션 없이 stateless 운영 가능.
 */
@Component
class HttpCookieOAuth2AuthorizationRequestRepository(
    private val objectMapper: ObjectMapper,
) : AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    override fun loadAuthorizationRequest(request: HttpServletRequest): OAuth2AuthorizationRequest? =
        getCookieValue(request)?.let { deserialize(it) }

    override fun saveAuthorizationRequest(
        authorizationRequest: OAuth2AuthorizationRequest?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (authorizationRequest == null) {
            deleteCookie(response)
            return
        }
        val value = serialize(authorizationRequest)
        val cookie = Cookie(COOKIE_NAME, value).apply {
            isHttpOnly = true
            path = "/"
            maxAge = COOKIE_MAX_AGE
            // sameSite=Lax는 Servlet Cookie API에 없어 Set-Cookie 헤더로 직접 추가
        }
        response.addCookie(cookie)
        // SameSite=Lax 명시 (브라우저 기본 동작과 일치하지만 명시적으로 설정)
        response.addHeader("Set-Cookie",
            "$COOKIE_NAME=$value; Path=/; Max-Age=$COOKIE_MAX_AGE; HttpOnly; SameSite=Lax")
    }

    override fun removeAuthorizationRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): OAuth2AuthorizationRequest? {
        val authRequest = loadAuthorizationRequest(request)
        deleteCookie(response)
        return authRequest
    }

    private fun getCookieValue(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value

    private fun deleteCookie(response: HttpServletResponse) {
        val cookie = Cookie(COOKIE_NAME, "").apply {
            isHttpOnly = true
            path = "/"
            maxAge = 0
        }
        response.addCookie(cookie)
    }

    private fun serialize(request: OAuth2AuthorizationRequest): String {
        val bytes = objectMapper.writeValueAsBytes(request)
        return Base64.getUrlEncoder().encodeToString(bytes)
    }

    private fun deserialize(value: String): OAuth2AuthorizationRequest? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(value)
        objectMapper.readValue(bytes, OAuth2AuthorizationRequest::class.java)
    }.getOrNull()
}
