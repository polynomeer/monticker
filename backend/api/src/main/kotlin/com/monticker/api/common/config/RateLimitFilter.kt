package com.monticker.api.common.config

import com.monticker.api.common.redis.RedisGuard
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

@Component
class RateLimitFilter(
    private val redis: StringRedisTemplate,
    private val guard: RedisGuard,
    // X-Bench 헤더 우회는 부하 테스트 편의 기능이다. 기본값 false — 운영에서 켜져 있으면
    // 헤더 한 줄로 레이트리밋 전체를 무력화할 수 있다 (resilience-plan §F5, P0-4).
    // local/dev 프로파일만 true로 둔다.
    @Value("\${app.rate-limit.bench-bypass-enabled:false}") private val benchBypassEnabled: Boolean,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (benchBypassEnabled && req.getHeader("X-Bench") == "true") {
            chain.doFilter(req, res)
            return
        }

        // NGINX 프록시 환경: X-Forwarded-For 헤더의 첫 번째 IP를 실제 클라이언트 IP로 사용한다.
        // 헤더가 없으면 직접 연결 IP를 사용한다.
        val ip   = req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                   ?: req.remoteAddr
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

    // Redis 장애 시 fail-open: 레이트리밋은 남용 방어이지 서비스 성립 조건이 아니다.
    // 이 필터는 /api/** 전체에 걸리므로, 여기서 예외가 새면 Redis 장애 = 전면 장애가 된다.
    private fun isRateLimited(key: String, limit: Int, window: Duration): Boolean =
        guard.failOpen(op = "rate_limit", fallback = false) {
            val count = redis.opsForValue().increment("rate:$key") ?: 1L
            if (count == 1L) redis.expire("rate:$key", window)
            count > limit
        }
}
