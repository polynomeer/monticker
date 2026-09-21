package com.monticker.api.auth.infrastructure

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * docs/security-review.md C2 — refresh token은 최대 7일간 유효한데, 응답 바디로 돌려주면
 * 클라이언트가 어디에 저장하든(localStorage든 메모리든) XSS 한 건으로 탈취 가능한 형태로
 * 노출된다. HttpOnly 쿠키로만 주고받으면 JS가 아예 읽을 수 없다.
 *
 * `Path=/api/auth`로 스코프를 좁힌다 — 이 쿠키가 브로커리지 주문 같은 일반 API 호출에는
 * 실리지 않게 해서, 혹시 모를 CSRF 표면을 인증 엔드포인트로만 최소화한다(그마저도
 * SameSite=Lax가 top-level GET 내비게이션 외의 교차 사이트 요청을 막는다).
 *
 * access token은 이 대상이 아니다 — 여전히 응답 바디 + `Authorization` 헤더로 오간다
 * (만료가 15분으로 짧아 노출 창이 훨씬 작고, 프론트 19곳 이상이 직접 참조하고 있어
 * 쿠키 전환의 실익 대비 회귀 리스크가 크다 — 별도 후속 작업으로 남겨둔다).
 */
@Component
class RefreshTokenCookie(
    @Value("\${app.cookie-secure:false}") private val cookieSecure: Boolean,
) {
    fun set(response: HttpServletResponse, refreshToken: String, maxAge: Duration) {
        val cookie = ResponseCookie.from(NAME, refreshToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(PATH)
            .maxAge(maxAge)
            .build()
        response.addHeader("Set-Cookie", cookie.toString())
    }

    fun clear(response: HttpServletResponse) {
        val cookie = ResponseCookie.from(NAME, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(PATH)
            .maxAge(0)
            .build()
        response.addHeader("Set-Cookie", cookie.toString())
    }

    fun read(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == NAME }?.value

    companion object {
        const val NAME = "refreshToken"
        private const val PATH = "/api/auth"
    }
}
