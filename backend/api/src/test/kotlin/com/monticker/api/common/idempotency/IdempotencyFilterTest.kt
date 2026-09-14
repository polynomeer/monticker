package com.monticker.api.common.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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

/**
 * resilience-plan §A1 / P0-1 — 멱등성은 fail-closed.
 * 실제 돈이 움직이는 엔드포인트에서 "혹시 중복일지 모르는 주문을 일단 실행"하지 않는다.
 */
class IdempotencyFilterTest {

    private val valueOps = mockk<ValueOperations<String, String>>()
    private val redis = mockk<StringRedisTemplate> { every { opsForValue() } returns valueOps }
    private val registry = SimpleMeterRegistry()
    private val filter = IdempotencyFilter(redis, ObjectMapper(), RedisGuard(registry))

    @BeforeEach
    fun auth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(42L, null, emptyList())
    }

    @AfterEach
    fun cleanup() = SecurityContextHolder.clearContext()

    private fun orderRequest() = MockHttpServletRequest("POST", "/api/brokerage/orders").apply {
        addHeader("X-Idempotency-Key", "k-1")
    }

    @Test
    fun `Redis 연결 실패 시 요청을 실행하지 않고 503과 Retry-After를 반환한다 (fail-closed)`() {
        every { valueOps.get(any<String>()) } throws RedisConnectionFailureException("down")
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(orderRequest(), res, chain)

        assertThat(res.status).isEqualTo(503)
        assertThat(res.getHeader("Retry-After")).isEqualTo("2")
        assertThat(res.characterEncoding).isEqualToIgnoringCase("UTF-8")   // 한글 메시지가 '?'로 깨지면 안 된다
        assertThat(res.contentAsString).contains("\"status\":503").contains("잠시 후 다시 시도해주세요")
        assertThat(chain.request).isNull()             // 주문 핸들러까지 도달하지 않았다
        assertThat(registry.counter("redis_command_failed_total", "op", "idempotency_get", "policy", "closed").count())
            .isEqualTo(1.0)
    }

    @Test
    fun `캐시 히트 시 이전 응답을 그대로 돌려준다`() {
        every { valueOps.get("idempotency:42:k-1") } returns """{"status":201,"body":"{\"orderId\":7}"}"""
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(orderRequest(), res, chain)

        assertThat(res.status).isEqualTo(201)
        assertThat(res.contentAsString).isEqualTo("""{"orderId":7}""")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `멱등성 대상이 아닌 경로는 Redis를 건드리지 않는다`() {
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(MockHttpServletRequest("GET", "/api/stocks/1"), res, chain)

        assertThat(chain.request).isNotNull
    }
}
