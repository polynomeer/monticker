package com.monticker.api.auth.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractToken(request)
        if (token != null && jwtTokenProvider.validateToken(token)) {
            try {
                val userId = jwtTokenProvider.getUserId(token)
                val role   = jwtTokenProvider.getRole(token)
                val auth = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_$role")),
                )
                SecurityContextHolder.getContext().authentication = auth
            } catch (e: Exception) {
                // L1 — refresh token은 서명은 유효하지만 role 클레임이 없다. 여기서 발급하는
                // 토큰은 access/refresh 둘뿐이라 이 필터에 refresh token이 Authorization
                // 헤더로 잘못 들어오면(오용) getRole()의 강제 캐스팅이 예외를 던진다. 필터에서
                // 던진 예외는 GlobalExceptionHandler를 거치지 않고 컨테이너 기본 500으로 샌다 —
                // 그냥 인증 미설정 상태로 두면 보호된 엔드포인트는 평범한 401로 떨어진다.
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ")
    }
}
