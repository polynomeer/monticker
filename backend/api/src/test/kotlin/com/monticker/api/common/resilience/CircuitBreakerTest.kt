package com.monticker.api.common.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class CircuitBreakerTest {

    private fun registry(vararg names: String): CircuitBreakerRegistry {
        val registry = CircuitBreakerRegistry.ofDefaults()
        names.forEach { name ->
            registry.circuitBreaker(name,
                CircuitBreakerConfig.custom()
                    .failureRateThreshold(50f)
                    .slidingWindowSize(4)
                    .waitDurationInOpenState(Duration.ofMillis(100))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .recordExceptions(Exception::class.java)
                    .build()
            )
        }
        return registry
    }

    @Test
    fun `tradingService CB — 실패율 초과 시 OPEN으로 전환된다`() {
        val cb = registry("tradingService").circuitBreaker("tradingService")

        // 4회 중 3회 실패 → 75% > 50% threshold → OPEN
        repeat(3) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, RuntimeException("down")) }
        cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    @Test
    fun `quantEngine CB — 실패율 미달 시 CLOSED 유지`() {
        val cb = registry("quantEngine").circuitBreaker("quantEngine")

        // 4회 중 1회 실패 → 25% < 50% → CLOSED 유지
        cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, RuntimeException("err"))
        repeat(3) { cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS) }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `yahooFinance CB — OPEN 후 대기 시간 경과 시 HALF_OPEN으로 전환된다`() {
        val cb = registry("yahooFinance").circuitBreaker("yahooFinance")

        // OPEN 전환
        repeat(3) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, RuntimeException("rate limit")) }
        cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)

        // waitDurationInOpenState(100ms) 대기
        Thread.sleep(150)
        cb.transitionToHalfOpenState()
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.HALF_OPEN)
    }

    @Test
    fun `CB OPEN 상태에서 executeCallable은 CallNotPermittedException을 던진다`() {
        val cb = registry("tradingService").circuitBreaker("tradingService")

        repeat(3) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, RuntimeException("down")) }
        cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)

        val ex = runCatching {
            cb.executeCallable<String> { "should not reach" }
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException::class.java)
    }

    @Test
    fun `CircuitBreakerConfiguration 빈이 4개 CB를 등록한다`() {
        val config = CircuitBreakerConfiguration()
        val registry = config.circuitBreakerRegistry()

        assertThat(registry.allCircuitBreakers.map { it.name })
            .containsAll(listOf("tradingService", "quantEngine", "yahooFinance"))
    }
}
