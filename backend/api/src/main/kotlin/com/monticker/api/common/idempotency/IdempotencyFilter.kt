package com.monticker.api.common.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.time.Duration

/**
 * X-Idempotency-Key 헤더 기반 멱등성 보장 필터.
 *
 * POST /api/paper/buy|sell, POST /api/matching/orders 에서 동일 키로 재요청이 들어오면
 * 이전 응답을 그대로 반환한다. 중복 주문 방지용.
 *
 * Redis 키: idempotency:{userId}:{X-Idempotency-Key}
 * TTL: 24시간
 */
@Component
class IdempotencyFilter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    private val idempotentPaths = setOf(
        "/api/paper/buy",
        "/api/paper/sell",
        "/api/matching/orders",
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (request.method != "POST") return true
        val path = request.requestURI
        return idempotentPaths.none { path == it }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val idempotencyKey = request.getHeader("X-Idempotency-Key")
        if (idempotencyKey.isNullOrBlank()) {
            chain.doFilter(request, response)
            return
        }

        val userId = runCatching {
            SecurityContextHolder.getContext().authentication?.principal as? Long
        }.getOrNull() ?: run {
            chain.doFilter(request, response)
            return
        }

        val redisKey = "idempotency:$userId:$idempotencyKey"

        val cached = redis.opsForValue().get(redisKey)
        if (cached != null) {
            log.debug("멱등성 캐시 히트: userId={} key={}", userId, idempotencyKey)
            val payload = objectMapper.readTree(cached)
            response.status = payload["status"].asInt()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(payload["body"].asText())
            return
        }

        val wrapped = ContentCachingResponseWrapper(response)
        chain.doFilter(request, wrapped)

        if (wrapped.status in 200..299) {
            val body = String(wrapped.contentAsByteArray, Charsets.UTF_8)
            val entry = objectMapper.writeValueAsString(mapOf("status" to wrapped.status, "body" to body))
            redis.opsForValue().set(redisKey, entry, Duration.ofHours(24))
            log.debug("멱등성 캐시 저장: userId={} key={} status={}", userId, idempotencyKey, wrapped.status)
        }

        wrapped.copyBodyToResponse()
    }
}
