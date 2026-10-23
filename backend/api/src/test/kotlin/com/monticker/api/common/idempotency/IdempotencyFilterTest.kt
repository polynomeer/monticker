package com.monticker.api.common.idempotency

import com.monticker.api.support.TestJson
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration

/**
 * resilience-plan §A1 / P0-1 — 멱등성은 fail-closed.
 * 실제 돈이 움직이는 엔드포인트에서 "혹시 중복일지 모르는 주문을 일단 실행"하지 않는다.
 *
 * ADR-110 — 실거래 주문은 처리 중 잠금·본문 바인딩·결과 불명 고정까지 건다.
 */
class IdempotencyFilterTest {

    private val valueOps = mockk<ValueOperations<String, String>>()
    private val redis = mockk<StringRedisTemplate> { every { opsForValue() } returns valueOps }
    private val registry = SimpleMeterRegistry()
    private val filter = IdempotencyFilter(redis, TestJson.boot, RedisGuard(registry))

    @BeforeEach
    fun auth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(42L, null, emptyList())
    }

    @AfterEach
    fun cleanup() = SecurityContextHolder.clearContext()

    private val order = """{"symbol":"005930","side":"BUY","orderType":"MARKET","quantity":1}"""

    private fun orderRequest(body: String = order) = MockHttpServletRequest("POST", "/api/brokerage/orders").apply {
        addHeader("X-Idempotency-Key", "k-1")
        contentType = "application/json"
        setContent(body.toByteArray())
    }

    /** 컨트롤러 흉내 — 본문을 읽고 정해진 상태·본문으로 답한다. */
    private class Handler(private val status: Int, private val body: String = "", private val before: (MockHttpServletRequest) -> Unit = {}) : FilterChain {
        var calls = 0
        var readBody: String? = null
        override fun doFilter(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
            calls++
            readBody = req.inputStream.readAllBytes().toString(Charsets.UTF_8)
            before(unwrap(req))
            (res as jakarta.servlet.http.HttpServletResponse).status = status
            res.contentType = "application/json"
            res.outputStream.write(body.toByteArray())
        }
        private fun unwrap(req: jakarta.servlet.ServletRequest): MockHttpServletRequest =
            req as? MockHttpServletRequest ?: unwrap((req as jakarta.servlet.ServletRequestWrapper).request)
    }

    private fun lockAcquired() {
        every { valueOps.setIfAbsent("idempotency:42:k-1", any(), Duration.ofHours(24)) } returns true
    }

    /** 첫 요청이 남긴 처리 중 표시(같은 본문의 해시)를 캡처해 둔다. */
    private fun markerFor(body: String = order): String {
        val captured = slot<String>()
        every { valueOps.setIfAbsent("idempotency:42:k-1", capture(captured), any<Duration>()) } returns true
        every { redis.delete("idempotency:42:k-1") } returns true
        filter.doFilter(orderRequest(body), MockHttpServletResponse(), Handler(400))
        return captured.captured
    }

    private fun storedEntry(): String {
        val stored = slot<String>()
        verify { valueOps.set("idempotency:42:k-1", capture(stored), Duration.ofHours(24)) }
        return stored.captured
    }

    @Test
    fun `Redis 연결 실패 시 요청을 실행하지 않고 503과 Retry-After를 반환한다 (fail-closed)`() {
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } throws RedisConnectionFailureException("down")
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(orderRequest(), res, chain)

        assertThat(res.status).isEqualTo(503)
        assertThat(res.getHeader("Retry-After")).isEqualTo("2")
        assertThat(res.characterEncoding).isEqualToIgnoringCase("UTF-8")   // 한글 메시지가 '?'로 깨지면 안 된다
        assertThat(res.contentAsString).contains("\"status\":503").contains("잠시 후 다시 시도해주세요")
        assertThat(chain.request).isNull()             // 주문 핸들러까지 도달하지 않았다
        assertThat(registry.counter("redis_command_failed_total", "op", "idempotency_lock", "policy", "closed").count())
            .isEqualTo(1.0)
    }

    @Test
    fun `ADR-110 이전에 저장된 캐시(본문 해시 없음)도 그대로 돌려준다`() {
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        every { valueOps.get("idempotency:42:k-1") } returns """{"status":201,"body":"{\"orderId\":7}"}"""
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(orderRequest(), res, chain)

        assertThat(res.status).isEqualTo(201)
        assertThat(res.contentAsString).isEqualTo("""{"orderId":7}""")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `같은 키의 요청이 처리 중이면 실행하지 않고 409 처리 중을 반환한다`() {
        val marker = markerFor()
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        every { valueOps.get("idempotency:42:k-1") } returns marker
        val res = MockHttpServletResponse()
        val chain = Handler(200)

        filter.doFilter(orderRequest(), res, chain)

        assertThat(res.status).isEqualTo(409)
        assertThat(res.contentAsString).contains("IDEMPOTENCY_IN_FLIGHT").contains("처리 중")
        assertThat(chain.calls).isZero()
    }

    @Test
    fun `같은 키에 다른 본문이면 실행하지 않고 422를 반환한다 — 처리 중일 때도`() {
        val marker = markerFor()
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        every { valueOps.get("idempotency:42:k-1") } returns marker
        val res = MockHttpServletResponse()
        val chain = Handler(200)

        filter.doFilter(orderRequest(order.replace("\"quantity\":1", "\"quantity\":100")), res, chain)

        assertThat(res.status).isEqualTo(422)
        assertThat(res.contentAsString).contains("IDEMPOTENCY_KEY_REUSED")
        assertThat(chain.calls).isZero()
    }

    @Test
    fun `성공하면 본문 해시와 함께 저장하고, 같은 본문의 재시도에는 실행 없이 같은 응답을 준다`() {
        lockAcquired()
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        val first = MockHttpServletResponse()
        val chain = Handler(200, """{"id":7,"status":"SUBMITTED"}""")

        filter.doFilter(orderRequest(), first, chain)

        assertThat(first.status).isEqualTo(200)
        assertThat(first.contentAsString).isEqualTo("""{"id":7,"status":"SUBMITTED"}""")
        assertThat(chain.readBody).isEqualTo(order)        // 필터가 해시하느라 읽은 본문을 컨트롤러가 그대로 읽는다
        val entry = storedEntry()
        assertThat(entry).contains("bodyHash")

        // 재시도 — 필드 순서만 다른 같은 주문
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        every { valueOps.get("idempotency:42:k-1") } returns entry
        val retry = MockHttpServletResponse()
        val retryChain = Handler(200)
        filter.doFilter(orderRequest("""{"quantity":1,"orderType":"MARKET","side":"BUY","symbol":"005930"}"""), retry, retryChain)

        assertThat(retryChain.calls).isZero()
        assertThat(retry.status).isEqualTo(200)
        assertThat(retry.contentAsString).isEqualTo("""{"id":7,"status":"SUBMITTED"}""")
    }

    @Test
    fun `5xx는 결과 불명으로 고정한다 — 응답을 결과 확인 필요로 바꾸고 재시도는 다시 실행되지 않는다`() {
        lockAcquired()
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        val first = MockHttpServletResponse()

        filter.doFilter(orderRequest(), first, Handler(500, """{"status":500,"message":"서버 내부 오류가 발생했습니다"}"""))

        assertThat(first.status).isEqualTo(500)
        assertThat(first.contentAsString).contains("주문 결과 확인이 필요합니다").contains("ORDER_OUTCOME_UNKNOWN")
        verify(exactly = 0) { redis.delete(any<String>()) }
        val entry = storedEntry()
        assertThat(entry).contains("INDETERMINATE")

        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        every { valueOps.get("idempotency:42:k-1") } returns entry
        val retry = MockHttpServletResponse()
        val retryChain = Handler(200)
        filter.doFilter(orderRequest(), retry, retryChain)

        assertThat(retryChain.calls).isZero()
        assertThat(retry.status).isEqualTo(500)
        assertThat(retry.contentAsString).isEqualTo(first.contentAsString)
    }

    @Test
    fun `결과 불명 예외(IndeterminateOutcome)는 4xx여도 고정하고 주문 id를 알려준다`() {
        lockAcquired()
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        val outcome = object : IndeterminateOutcome { override val orderId: Long = 7 }
        val res = MockHttpServletResponse()

        filter.doFilter(orderRequest(), res, Handler(409, "{}") { it.setAttribute(IndeterminateOutcome.REQUEST_ATTRIBUTE, outcome) })

        assertThat(res.status).isEqualTo(409)
        assertThat(res.contentAsString).contains("\"orderId\":7").contains("주문 #7")
        verify(exactly = 0) { redis.delete(any<String>()) }
        assertThat(storedEntry()).contains("INDETERMINATE")
    }

    @Test
    fun `핸들러 밖으로 예외가 새면 결과 불명으로 고정하고 예외는 그대로 던진다`() {
        lockAcquired()
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        val chain = FilterChain { _, _ -> throw IllegalStateException("boom") }

        assertThatThrownBy { filter.doFilter(orderRequest(), MockHttpServletResponse(), chain) }
            .hasMessageContaining("boom")
        assertThat(storedEntry()).contains("INDETERMINATE")
    }

    @Test
    fun `확정 실패(그 밖의 4xx)는 키를 풀어 같은 키로 다시 보낼 수 있게 한다`() {
        lockAcquired()
        every { redis.delete("idempotency:42:k-1") } returns true
        val res = MockHttpServletResponse()

        filter.doFilter(orderRequest(), res, Handler(422, """{"status":422,"message":"리스크 한도 초과"}"""))

        assertThat(res.status).isEqualTo(422)
        assertThat(res.contentAsString).contains("리스크 한도 초과")
        verify { redis.delete("idempotency:42:k-1") }
        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
    }

    @Test
    fun `결과 저장에 실패해도 처리 중 표시가 남아 재시도가 다시 실행되지 않는다`() {
        val marker = markerFor()
        lockAcquired()
        every { valueOps.set(any(), any(), any<Duration>()) } throws RedisConnectionFailureException("down")
        val res = MockHttpServletResponse()

        filter.doFilter(orderRequest(), res, Handler(200, """{"id":7}"""))

        assertThat(res.status).isEqualTo(200)                // 주문은 실제로 나갔다 — 응답은 정상으로 준다
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        every { valueOps.get("idempotency:42:k-1") } returns marker
        val retry = MockHttpServletResponse()
        filter.doFilter(orderRequest(), retry, Handler(200))
        assertThat(retry.status).isEqualTo(409)
    }

    @Test
    fun `모의투자 등 다른 경로는 예전처럼 잠금 없이 2xx만 저장한다`() {
        every { valueOps.get("idempotency:42:k-1") } returns null
        val res = MockHttpServletResponse()
        val req = MockHttpServletRequest("POST", "/api/paper/buy").apply { addHeader("X-Idempotency-Key", "k-1") }

        filter.doFilter(req, res, Handler(500, "{}"))

        assertThat(res.status).isEqualTo(500)
        verify(exactly = 0) { valueOps.setIfAbsent(any(), any(), any<Duration>()) }
        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
    }

    @Test
    fun `멱등성 대상이 아닌 경로는 Redis를 건드리지 않는다`() {
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(MockHttpServletRequest("GET", "/api/stocks/1"), res, chain)

        assertThat(chain.request).isNotNull
    }
}
