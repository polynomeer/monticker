package com.monticker.api.common.aop

import io.mockk.*
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

class RateLimitedAspectTest {

    private val redis = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val aspect = RateLimitedAspect(redis)

    @BeforeEach
    fun setup() {
        every { redis.opsForValue() } returns valueOps
        every { redis.expire(any<String>(), any<Duration>()) } returns true
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun cleanup() = SecurityContextHolder.clearContext()

    private fun makeAnnotation(limit: Int, windowSec: Long, keyPrefix: String): RateLimited =
        mockk {
            every { this@mockk.limit } returns limit
            every { this@mockk.windowSec } returns windowSec
            every { this@mockk.keyPrefix } returns keyPrefix
        }

    private fun makePjp(paramNames: Array<String> = emptyArray(), args: Array<Any?> = emptyArray()): ProceedingJoinPoint {
        val sig = mockk<MethodSignature> {
            every { parameterNames } returns paramNames
            every { declaringType } returns this@RateLimitedAspectTest.javaClass
            every { name } returns "testMethod"
        }
        return mockk {
            every { signature } returns sig
            every { this@mockk.args } returns args
            every { proceed() } returns "result"
        }
    }

    @Test
    fun `한도 이내 요청은 정상 처리된다`() {
        every { valueOps.increment(any<String>()) } returns 1L

        val result = aspect.limit(makePjp(), makeAnnotation(10, 60, "test"))

        assertThat(result).isEqualTo("result")
    }

    @Test
    fun `한도 초과 시 429를 던진다`() {
        every { valueOps.increment(any<String>()) } returns 11L

        val ex = assertThrows<ResponseStatusException> {
            aspect.limit(makePjp(), makeAnnotation(10, 60, "test"))
        }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(ex.reason).contains("test")
    }

    @Test
    fun `SecurityContextHolder에서 userId를 추출해 키를 구성한다`() {
        val auth = UsernamePasswordAuthenticationToken(42L, null, emptyList())
        SecurityContextHolder.getContext().authentication = auth

        val capturedKey = mutableListOf<String>()
        every { valueOps.increment(capture(capturedKey)) } returns 1L

        aspect.limit(makePjp(), makeAnnotation(10, 60, "ai.summary"))

        assertThat(capturedKey.first()).isEqualTo("ratelimit:ai.summary:42")
    }

    @Test
    fun `SecurityContextHolder 없으면 anon 키를 사용한다`() {
        val capturedKey = mutableListOf<String>()
        every { valueOps.increment(capture(capturedKey)) } returns 1L

        aspect.limit(makePjp(), makeAnnotation(10, 60, "test"))

        assertThat(capturedKey.first()).isEqualTo("ratelimit:test:anon")
    }

    @Test
    fun `파라미터명이 userId면 해당 값을 키로 사용한다`() {
        val capturedKey = mutableListOf<String>()
        every { valueOps.increment(capture(capturedKey)) } returns 1L

        aspect.limit(
            makePjp(paramNames = arrayOf("userId"), args = arrayOf(99L)),
            makeAnnotation(10, 60, "quant.backtest"),
        )

        assertThat(capturedKey.first()).isEqualTo("ratelimit:quant.backtest:99")
    }

    @Test
    fun `첫 요청 시 TTL을 설정한다`() {
        every { valueOps.increment(any<String>()) } returns 1L

        aspect.limit(makePjp(), makeAnnotation(10, 300, "test"))

        verify { redis.expire("ratelimit:test:anon", Duration.ofSeconds(300)) }
    }

    @Test
    fun `keyPrefix가 비어 있으면 클래스명 메서드명으로 키를 만든다`() {
        val capturedKey = mutableListOf<String>()
        every { valueOps.increment(capture(capturedKey)) } returns 1L

        aspect.limit(makePjp(), makeAnnotation(10, 60, ""))

        assertThat(capturedKey.first()).startsWith("ratelimit:RateLimitedAspectTest.testMethod:")
    }
}
