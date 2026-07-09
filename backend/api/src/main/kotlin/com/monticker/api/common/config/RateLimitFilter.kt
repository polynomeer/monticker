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

        // 엔드포인트별 IP 기반 제한. 인증 엔드포인트는 브루트포스 방어를 위해 더 엄격히 적용한다.
        data class Limit(val key: String, val max: Int, val window: Duration)
        val limit = when {
            path == "/api/auth/login"      -> Limit("auth.login:$ip",   10, Duration.ofMinutes(1))
            path == "/api/auth/signup"     -> Limit("auth.signup:$ip",   5, Duration.ofMinutes(10))
            path == "/api/auth/refresh"    -> Limit("auth.refresh:$ip", 20, Duration.ofMinutes(1))
            path.startsWith("/api/auth/")  -> Limit("auth:$ip",         30, Duration.ofMinutes(1))
            path.startsWith("/api/")       -> Limit("api:$ip",         300, Duration.ofMinutes(1))
            else                           -> { chain.doFilter(req, res); return }
        }

        if (isRateLimited(limit.key, limit.max, limit.window)) {
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
