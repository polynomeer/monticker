package com.monticker.api.common.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.redis.RedisGuard
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.time.Duration
import java.time.Instant

/**
 * X-Idempotency-Key 헤더 기반 멱등성 보장 필터.
 *
 * POST /api/paper/buy|sell, /api/matching/orders, /api/subscription/payment/confirm 에서
 * 동일 키로 재요청이 들어오면 이전 응답을 그대로 반환한다. 중복 주문/중복 결제 확정 방지용
 * (특히 결제 confirm은 네트워크 타임아웃으로 프론트가 재시도하기 쉬운 엔드포인트라 필요하다).
 *
 * Redis 키: idempotency:{userId}:{X-Idempotency-Key}
 * TTL: 24시간
 *
 * Redis 장애 시 fail-closed (resilience-plan §A1, P0-1): 멱등성 키를 확인할 수 없으면
 * 요청을 실행하지 않고 503 + Retry-After로 거절한다. 레이트리밋과 반대 정책인 이유 —
 * 이 필터가 보호하는 엔드포인트는 전부 실제 돈이 움직이는 곳이라, "혹시 중복일지 모르는
 * 주문을 일단 실행"하는 것보다 "잠시 거절"이 낫다.
 */
@Component
class IdempotencyFilter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val guard: RedisGuard,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    private val idempotentPaths = setOf(
        "/api/paper/buy",
        "/api/paper/sell",
        "/api/matching/orders",
        "/api/subscription/payment/confirm",
        // ADR-026 — 실제 돈이 이동하는 실거래 주문. 프론트 재시도로 인한 중복 제출을 막는다.
        // Toss의 clientOrderId(모나티커→Toss)와는 별개 레이어 — 이건 브라우저→모나티커 요청을 보호한다.
        "/api/brokerage/orders",
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

        // Optional<String>으로 감싸는 이유: failClosed는 "Redis 실패"를 null로 알리는데,
        // 캐시 미스도 null이라 둘을 구분할 수 없기 때문이다.
        val lookup = guard.failClosed(op = "idempotency_get") {
            java.util.Optional.ofNullable(redis.opsForValue().get(redisKey))
        }
        if (lookup == null) {
            rejectUnavailable(response, userId, idempotencyKey)
            return
        }
        val cached: String? = lookup.orElse(null)
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
            // 여기서 실패하면 이미 실행된 요청을 되돌릴 수 없다 — 클라이언트가 같은 키로 재시도하면
            // 중복이 생길 수 있다. 응답은 정상 반환하되(주문은 실제로 체결됐다) ERROR로 키를 남겨
            // 조사할 수 있게 한다. 근본 해결은 DB 기반 멱등성 저장소(별도 결정)다.
            val stored = guard.failClosed(op = "idempotency_set") {
                redis.opsForValue().set(redisKey, entry, Duration.ofHours(24)); true
            }
            if (stored == null) {
                log.error("멱등성 캐시 저장 실패 — 재시도 시 중복 위험: userId={} key={} path={}",
                    userId, idempotencyKey, request.requestURI)
            } else {
                log.debug("멱등성 캐시 저장: userId={} key={} status={}", userId, idempotencyKey, wrapped.status)
            }
        }

        wrapped.copyBodyToResponse()
    }

    private fun rejectUnavailable(response: HttpServletResponse, userId: Long, key: String) {
        log.warn("멱등성 저장소 불가 — 요청 거절(fail-closed): userId={} key={}", userId, key)
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.setHeader("Retry-After", "2")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        // GlobalExceptionHandler와 같은 형태 — 필터 레이어라 핸들러를 거치지 않으므로 직접 만든다.
        response.writer.write(objectMapper.writeValueAsString(mapOf(
            "status"    to 503,
            "message"   to "잠시 후 다시 시도해주세요",
            "detail"    to "요청 중복 방지 저장소에 일시적으로 접근할 수 없습니다",
            "timestamp" to Instant.now().toString(),
        )))
    }
}
