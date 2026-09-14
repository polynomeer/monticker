package com.monticker.api.common.config

import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * resilience-plan §A1 / P0-1, §F5 / P0-4.
 * 이 필터는 /api/ 하위 전체에 걸리므로, 여기서 Redis 예외가 새면 Redis 장애 = 전면 장애다.
 */
class RateLimitFilterTest {

    private val valueOps = mockk<ValueOperations<String, String>>()
    private val redis = mockk<StringRedisTemplate> {
        every { opsForValue() } returns valueOps
        every { expire(any<String>(), any()) } returns true
    }
    private val registry = SimpleMeterRegistry()

    private fun filter(benchBypass: Boolean = false) =
        RateLimitFilter(redis, RedisGuard(registry), benchBypass)

    private fun request(path: String, vararg headers: Pair<String, String>) =
        MockHttpServletRequest("GET", path).apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    @Test
    fun `Redis 연결 실패 시 요청을 통과시킨다 (fail-open)`() {
        every { valueOps.increment(any<String>()) } throws RedisConnectionFailureException("down")
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter().doFilter(request("/api/stocks/1"), res, chain)

        assertThat(res.status).isEqualTo(200)
        assertThat(chain.request).isNotNull            // 체인이 실제로 진행됐다
        assertThat(registry.counter("redis_command_failed_total", "op", "rate_limit", "policy", "open").count())
            .isEqualTo(1.0)
    }

    @Test
    fun `한도 초과 시 429`() {
        every { valueOps.increment(any<String>()) } returns 301L
        val res = MockHttpServletResponse()

        filter().doFilter(request("/api/stocks/1"), res, MockFilterChain())

        assertThat(res.status).isEqualTo(429)
    }

    @Test
    fun `bench bypass가 꺼져 있으면 X-Bench 헤더가 있어도 레이트리밋을 적용한다`() {
        every { valueOps.increment(any<String>()) } returns 301L
        val res = MockHttpServletResponse()

        filter(benchBypass = false).doFilter(request("/api/stocks/1", "X-Bench" to "true"), res, MockFilterChain())

        assertThat(res.status).isEqualTo(429)
        verify(exactly = 1) { valueOps.increment(any<String>()) }
    }

    @Test
    fun `bench bypass가 켜져 있으면 X-Bench 헤더로 우회한다`() {
        val res = MockHttpServletResponse()

        filter(benchBypass = true).doFilter(request("/api/stocks/1", "X-Bench" to "true"), res, MockFilterChain())

        assertThat(res.status).isEqualTo(200)
        verify(exactly = 0) { valueOps.increment(any<String>()) }
    }
}
