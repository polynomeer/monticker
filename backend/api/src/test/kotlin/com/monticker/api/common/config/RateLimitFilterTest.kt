package com.monticker.api.common.config

import com.monticker.api.common.http.ClientIpResolver
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
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

    private fun filter(benchBypass: Boolean = false, trustedProxies: String = "") =
        RateLimitFilter(redis, RedisGuard(registry), ClientIpResolver(trustedProxies), benchBypass)

    private fun request(path: String, vararg headers: Pair<String, String>) =
        MockHttpServletRequest("GET", path).apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    @Test
    fun `Redis 연결 실패 시 요청을 통과시킨다 (fail-open)`() {
        every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any<String>()) } throws RedisConnectionFailureException("down")
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
        every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any<String>()) } returns 301L
        val res = MockHttpServletResponse()

        filter().doFilter(request("/api/stocks/1"), res, MockFilterChain())

        assertThat(res.status).isEqualTo(429)
    }

    @Test
    fun `bench bypass가 꺼져 있으면 X-Bench 헤더가 있어도 레이트리밋을 적용한다`() {
        every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any<String>()) } returns 301L
        val res = MockHttpServletResponse()

        filter(benchBypass = false).doFilter(request("/api/stocks/1", "X-Bench" to "true"), res, MockFilterChain())

        assertThat(res.status).isEqualTo(429)
        verify(exactly = 1) { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any<String>()) }
    }

    @Test
    fun `bench bypass가 켜져 있으면 X-Bench 헤더로 우회한다`() {
        val res = MockHttpServletResponse()

        filter(benchBypass = true).doFilter(request("/api/stocks/1", "X-Bench" to "true"), res, MockFilterChain())

        assertThat(res.status).isEqualTo(200)
        verify(exactly = 0) { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), any<String>()) }
    }

    @Test
    fun `신뢰 프록시가 없으면 스푸핑된 X-Forwarded-For는 버킷 키에 쓰이지 않는다 (ADR-084)`() {
        val keys = mutableListOf<List<String>>()
        every { redis.execute(any<RedisScript<Long>>(), capture(keys), any<String>()) } returns 1L

        val req = request("/api/auth/login", "X-Forwarded-For" to "6.6.6.6").apply { remoteAddr = "203.0.113.7" }
        filter().doFilter(req, MockHttpServletResponse(), MockFilterChain())

        assertThat(keys.single().single()).isEqualTo("rate:auth.login:203.0.113.7")
    }

    @Test
    fun `신뢰 프록시 뒤에서는 프록시가 덧붙인 클라이언트 IP로 버킷을 나눈다 (ADR-084)`() {
        val keys = mutableListOf<List<String>>()
        every { redis.execute(any<RedisScript<Long>>(), capture(keys), any<String>()) } returns 1L

        val req = request("/api/auth/login", "X-Forwarded-For" to "6.6.6.6, 198.51.100.20")
            .apply { remoteAddr = "10.244.1.5" }
        filter(trustedProxies = "10.0.0.0/8").doFilter(req, MockHttpServletResponse(), MockFilterChain())

        assertThat(keys.single().single()).isEqualTo("rate:auth.login:198.51.100.20")
    }
}
