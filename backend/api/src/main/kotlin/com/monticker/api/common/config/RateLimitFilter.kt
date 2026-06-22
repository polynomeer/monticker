package com.monticker.api.common.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

@Component
class RateLimitFilter(private val redis: StringRedisTemplate) : OncePerRequestFilter() {

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val ip = req.remoteAddr
        val path = req.requestURI
        if (path.startsWith("/api/auth/")) {
            // 인증 엔드포인트: IP당 분당 20회
            if (isRateLimited("auth:$ip", 20, Duration.ofMinutes(1))) {
                res.sendError(429, "Too Many Requests")
                return
            }
        } else if (path.startsWith("/api/")) {
            // 일반 API: IP당 분당 120회
            if (isRateLimited("api:$ip", 120, Duration.ofMinutes(1))) {
                res.sendError(429, "Too Many Requests")
                return
            }
        }
        chain.doFilter(req, res)
    }

    private fun isRateLimited(key: String, limit: Int, window: Duration): Boolean {
        val count = redis.opsForValue().increment("rate:$key") ?: 1L
        if (count == 1L) redis.expire("rate:$key", window)
        return count > limit
    }
}
