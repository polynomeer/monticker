package com.monticker.api.common.idempotency

import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import com.monticker.api.common.redis.RedisGuard
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.Optional

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
 *
 * 실거래 주문(POST /api/brokerage/orders)은 더 엄격하다 — ADR-110:
 *  - 실행 전에 SETNX로 처리 중 표시를 건다. 같은 키의 동시 요청은 실행하지 않고 409 "처리 중".
 *  - 요청 본문 해시를 키에 묶는다. 같은 키에 다른 본문이면 422(다른 주문의 응답을 돌려주지 않는다).
 *  - 결과 불명(5xx, 또는 [IndeterminateOutcome] 예외)은 "결과 확인 필요" 응답으로 고정한다 — 같은 키 재시도가
 *    주문을 다시 실행하지 않는다. 확정 실패(그 밖의 4xx, 증권사 호출 전)만 키를 풀어 재시도를 허용한다.
 */
@Component
class IdempotencyFilter(
    private val redis: StringRedisTemplate,
    private val objectMapper: JsonMapper,
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

    // ADR-110 — 처리 중 잠금·본문 바인딩·결과 불명 고정을 적용하는 경로. 나머지는 ADR-007 동작 그대로다.
    private val strictPaths = setOf("/api/brokerage/orders")

    // 키 순서가 달라도 같은 주문이면 같은 해시가 나오도록 정렬해서 직렬화한다.
    private val canonicalWriter = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

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

        if (request.requestURI in strictPaths) {
            filterStrict(request, response, chain, userId, idempotencyKey, redisKey)
            return
        }

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

    /** ADR-110 — 실거래 주문용. 처리 중 잠금 → 실행 → 결과에 따라 고정하거나 잠금을 푼다. */
    private fun filterStrict(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
        userId: Long,
        idempotencyKey: String,
        redisKey: String,
    ) {
        val body = request.inputStream.readAllBytes()
        val bodyHash = hashBody(body)

        // 처리 중 표시도 결과와 같은 24시간을 둔다 — 실행 도중 서버가 죽어 표시가 남으면 그 키는 끝까지 "처리 중"이다.
        // 짧게 두면 만료 뒤 같은 키 재시도가 주문을 다시 실행한다. 새 주문은 클라이언트가 새 키로 보내므로 막히지 않는다.
        val marker = objectMapper.writeValueAsString(mapOf("state" to STATE_IN_FLIGHT, "bodyHash" to bodyHash))
        val acquired = guard.failClosed(op = "idempotency_lock") {
            redis.opsForValue().setIfAbsent(redisKey, marker, TTL) == true
        }
        if (acquired == null) {
            rejectUnavailable(response, userId, idempotencyKey)
            return
        }
        if (!acquired) {
            val lookup = guard.failClosed(op = "idempotency_get") {
                Optional.ofNullable(redis.opsForValue().get(redisKey))
            }
            if (lookup == null) {
                rejectUnavailable(response, userId, idempotencyKey)
                return
            }
            respondToExisting(response, lookup.orElse(null), bodyHash, userId, idempotencyKey)
            return
        }

        val wrapped = ContentCachingResponseWrapper(response)
        try {
            chain.doFilter(CachedBodyRequest(request, body), wrapped)
        } catch (e: Exception) {
            // 핸들러가 응답을 만들지 못하고 예외가 빠져나왔다 — 주문이 나갔는지 모른다. 컨테이너가 500을 쓰게 두되 키는 고정한다.
            store(redisKey, indeterminateEntry(HttpStatus.INTERNAL_SERVER_ERROR.value(), null, bodyHash), userId, idempotencyKey, request)
            throw e
        }

        val indeterminate = request.getAttribute(IndeterminateOutcome.REQUEST_ATTRIBUTE) as? IndeterminateOutcome
        when {
            indeterminate == null && wrapped.status in 200..299 -> {
                val responseBody = String(wrapped.contentAsByteArray, Charsets.UTF_8)
                store(redisKey, objectMapper.writeValueAsString(
                    mapOf("status" to wrapped.status, "body" to responseBody, "bodyHash" to bodyHash)), userId, idempotencyKey, request)
                wrapped.copyBodyToResponse()
            }
            // 5xx는 증권사 호출 전인지 후인지 응답만으로 구분할 수 없다 — 실제 돈이 걸린 경로라 "나갔을 수 있다"로 본다.
            indeterminate != null || wrapped.status >= 500 -> {
                val entry = indeterminateEntry(wrapped.status, indeterminate?.orderId, bodyHash)
                store(redisKey, entry, userId, idempotencyKey, request)
                log.warn("주문 결과 불명 — 키를 결과 확인 필요로 고정: userId={} key={} status={} orderId={}",
                    userId, idempotencyKey, wrapped.status, indeterminate?.orderId)
                // 핸들러가 만든 본문은 버리고 재시도 때와 같은 본문을 보낸다 — 첫 응답과 재시도 응답이 달라지지 않게.
                val payload = objectMapper.readTree(entry)
                writeJson(response, payload["status"].asInt(), payload["body"].asText())
            }
            else -> {
                // 확정 실패(검증·리스크·킬 스위치 등, 증권사 호출 전) — 고친 뒤 같은 키로 다시 보낼 수 있게 푼다.
                // 풀기에 실패하면 키는 24시간 "처리 중"으로 남는다. 중복 실행 쪽이 아니라 거절 쪽으로 틀리는 것이라 안전하다.
                guard.failClosed(op = "idempotency_release") { redis.delete(redisKey) }
                    ?: log.warn("멱등성 처리 중 표시 해제 실패 — 키가 24시간 처리 중으로 남는다: userId={} key={}", userId, idempotencyKey)
                wrapped.copyBodyToResponse()
            }
        }
    }

    private fun respondToExisting(
        response: HttpServletResponse,
        existing: String?,
        bodyHash: String,
        userId: Long,
        idempotencyKey: String,
    ) {
        // SETNX 실패와 GET 사이에 만료됐다 — 앞선 요청이 있었던 건 분명하니 처리 중으로 답한다.
        val entry = existing?.let { objectMapper.readTree(it) }
        val storedHash = entry?.get("bodyHash")?.asText()
        when {
            // bodyHash가 없는 건 ADR-110 이전에 저장된 항목이다 — 비교할 수 없으니 예전처럼 그대로 돌려준다.
            storedHash != null && storedHash != bodyHash -> {
                log.warn("멱등성 키 재사용 — 다른 본문: userId={} key={}", userId, idempotencyKey)
                writeError(response, HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "같은 요청 키로 다른 주문이 들어왔습니다",
                    "X-Idempotency-Key는 주문 한 건에만 쓸 수 있습니다. 새 주문에는 새 키를 보내주세요.",
                    "IDEMPOTENCY_KEY_REUSED")
            }
            entry == null || entry["state"]?.asText() == STATE_IN_FLIGHT -> {
                log.info("멱등성 처리 중 중복 요청 거절: userId={} key={}", userId, idempotencyKey)
                writeError(response, HttpStatus.CONFLICT.value(),
                    "같은 주문을 처리 중입니다",
                    "앞선 요청이 아직 끝나지 않았습니다. 다시 주문하기 전에 주문 내역에서 결과를 확인해주세요.",
                    "IDEMPOTENCY_IN_FLIGHT")
            }
            else -> {
                log.debug("멱등성 캐시 히트: userId={} key={}", userId, idempotencyKey)
                writeJson(response, entry["status"].asInt(), entry["body"].asText())
            }
        }
    }

    private fun indeterminateEntry(status: Int, orderId: Long?, bodyHash: String): String {
        val detail = if (orderId != null) {
            "주문 #$orderId 이(가) 증권사에 전달됐을 수 있습니다. 다시 주문하기 전에 주문 내역에서 결과를 확인해주세요."
        } else {
            "주문이 증권사에 전달됐을 수 있습니다. 다시 주문하기 전에 주문 내역에서 결과를 확인해주세요."
        }
        val body = objectMapper.writeValueAsString(mapOf(
            "status"    to status,
            "message"   to "주문 결과 확인이 필요합니다",
            "detail"    to detail,
            "code"      to "ORDER_OUTCOME_UNKNOWN",
            "orderId"   to orderId,
            "timestamp" to Instant.now().toString(),
        ))
        return objectMapper.writeValueAsString(mapOf("status" to status, "body" to body, "bodyHash" to bodyHash, "outcome" to "INDETERMINATE"))
    }

    private fun store(redisKey: String, entry: String, userId: Long, idempotencyKey: String, request: HttpServletRequest) {
        // 실패하면 처리 중 표시가 남아 같은 키 재시도는 409를 받는다 — 다시 실행되지는 않는다(ADR-007 시절엔 재실행됐다).
        val stored = guard.failClosed(op = "idempotency_set") {
            redis.opsForValue().set(redisKey, entry, TTL); true
        }
        if (stored == null) {
            log.error("멱등성 결과 저장 실패 — 키는 처리 중으로 남는다: userId={} key={} path={}",
                userId, idempotencyKey, request.requestURI)
        }
    }

    private fun hashBody(body: ByteArray): String {
        val canonical = runCatching { canonicalWriter.writeValueAsBytes(objectMapper.readValue(body, Any::class.java)) }
            .getOrDefault(body)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String, detail: String, code: String) =
        writeJson(response, status, objectMapper.writeValueAsString(mapOf(
            "status"    to status,
            "message"   to message,
            "detail"    to detail,
            "code"      to code,
            "timestamp" to Instant.now().toString(),
        )))

    private fun writeJson(response: HttpServletResponse, status: Int, body: String) {
        response.status = status
        response.characterEncoding = "UTF-8"
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(body)
    }

    private fun rejectUnavailable(response: HttpServletResponse, userId: Long, key: String) {
        log.warn("멱등성 저장소 불가 — 요청 거절(fail-closed): userId={} key={}", userId, key)
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.setHeader("Retry-After", "2")
        // 서블릿 기본 인코딩은 ISO-8859-1이라 한글 메시지가 '?'로 깨진다 — CH-01 실험에서 실제로 확인했다.
        response.characterEncoding = "UTF-8"
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        // GlobalExceptionHandler와 같은 형태 — 필터 레이어라 핸들러를 거치지 않으므로 직접 만든다.
        response.writer.write(objectMapper.writeValueAsString(mapOf(
            "status"    to 503,
            "message"   to "잠시 후 다시 시도해주세요",
            "detail"    to "요청 중복 방지 저장소에 일시적으로 접근할 수 없습니다",
            "timestamp" to Instant.now().toString(),
        )))
    }

    private companion object {
        val TTL: Duration = Duration.ofHours(24)
        const val STATE_IN_FLIGHT = "IN_FLIGHT"
    }
}

/** 필터가 해시를 내려고 읽은 본문을 컨트롤러가 다시 읽을 수 있게 돌려준다. */
private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream {
        val source = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read() = source.read()
            override fun read(b: ByteArray, off: Int, len: Int) = source.read(b, off, len)
            override fun isFinished() = source.available() == 0
            override fun isReady() = true
            override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
        }
    }

    override fun getReader() = BufferedReader(InputStreamReader(inputStream, Charset.forName(characterEncoding ?: "UTF-8")))
}
