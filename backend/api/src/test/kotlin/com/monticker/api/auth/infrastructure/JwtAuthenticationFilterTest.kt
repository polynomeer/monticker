package com.monticker.api.auth.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder

class JwtAuthenticationFilterTest {

    private val provider = JwtTokenProvider(
        secret = "test-secret-key-that-is-at-least-32-bytes!!",
        accessTokenExpiryMs = 900_000L,
        refreshTokenExpiryMs = 604_800_000L,
    )
    private val filter = JwtAuthenticationFilter(provider)
    private val response = mockk<HttpServletResponse>(relaxed = true)
    private val chain = mockk<FilterChain>(relaxed = true)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    // OncePerRequestFilter.doFilterInternal은 protected라 테스트에서 직접 호출할 수 없다 —
    // 공개 API인 doFilter(단순 위임)를 통해 호출한다. relaxed 목이라 "이미 처리됨" 추적용
    // request attribute 읽기/쓰기는 기본값으로 통과한다.
    private fun requestWithAuth(token: String?): HttpServletRequest {
        val request = mockk<HttpServletRequest>(relaxed = true)
        every { request.getHeader("Authorization") } returns token?.let { "Bearer $it" }
        // relaxed 목은 getAttribute()에 기본적으로 non-null을 돌려줘 OncePerRequestFilter의
        // "이미 처리됨" 체크에 걸려 doFilterInternal 자체를 건너뛴다 — 명시적으로 null 처리.
        every { request.getAttribute(any()) } returns null
        return request
    }

    @Test
    fun `sets authentication for a valid access token`() {
        val token = provider.generateAccessToken(1L, "u@test.com", "USER")

        filter.doFilter(requestWithAuth(token), response, chain)

        val auth = SecurityContextHolder.getContext().authentication
        assertThat(auth).isNotNull()
        assertThat(auth.principal).isEqualTo(1L)
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `a refresh token used as a bearer token does not throw and leaves the request unauthenticated`() {
        // L1 — refresh token은 서명은 유효하지만 role 클레임이 없어 getRole()의 강제 캐스팅이
        // 예외를 던진다. 필터가 그 예외를 삼키지 못하면 GlobalExceptionHandler를 거치지 못하고
        // 500으로 샌다(docs/security-review.md P2-5) — 조용히 인증 미설정 상태로만 넘어가야 한다.
        val refreshToken = provider.generateRefreshToken(1L)

        filter.doFilter(requestWithAuth(refreshToken), response, chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `a garbage token does not throw and leaves the request unauthenticated`() {
        filter.doFilter(requestWithAuth("not-a-jwt"), response, chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `no Authorization header leaves the request unauthenticated`() {
        filter.doFilter(requestWithAuth(null), response, chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify { chain.doFilter(any(), any()) }
    }
}
