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
        // 벤치마크 요청은 rate limit 제외
        if (req.getHeader("X-Bench") == "true") {
            chain.doFilter(req, res)
            return
        }

        val ip   = req.remoteAddr
        val path = req.requestURI

        val (key, limit) = when {
            path.startsWith("/api/auth/") -> "auth:$ip" to 20
            path.startsWith("/api/")      -> "api:$ip"  to 120
            else                          -> { chain.doFilter(req, res); return }
        }

        if (isRateLimited(key, limit, Duration.ofMinutes(1))) {
            res.sendError(429, "Too Many Requests")
            return
        }

        chain.doFilter(req, res)
    }

    private fun isRateLimited(key: String, limit: Int, window: Duration): Boolean {
        val count = redis.opsForValue().increment("rate:$key") ?: 1L
        if (count == 1L) redis.expire("rate:$key", window)
        return count > limit
    }
}
