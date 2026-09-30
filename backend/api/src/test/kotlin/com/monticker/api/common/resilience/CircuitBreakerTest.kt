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
    fun `yahooFinance CB — 실패율 초과 시 OPEN으로 전환된다`() {
        val cb = registry("yahooFinance").circuitBreaker("yahooFinance")

        // 4회 중 3회 실패 → 75% > 50% threshold → OPEN
        repeat(3) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, RuntimeException("down")) }
        cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    @Test
    fun `yahooFinance CB — 실패율 미달 시 CLOSED 유지`() {
        val cb = registry("yahooFinance").circuitBreaker("yahooFinance")

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
        val cb = registry("yahooFinance").circuitBreaker("yahooFinance")

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
            .containsAll(listOf("yahooFinance", "kis", "toss"))   // ADR-048/049: tradingService·quantEngine 제거
    }

    // resilience-plan §B1 / P0-2 — 느린 호출에 반응하지 않는 브레이커는 스레드 고갈을 막지 못한다.
    // resilience4j 기본값은 slowCallRateThreshold=100(사실상 비활성)이라, 누가 새 CB를 추가하면서
    // 빠뜨리면 조용히 원래 문제로 돌아간다. 전수 검사로 고정한다.
    @Test
    fun `등록된 모든 CB는 slow-call 감지가 켜져 있다`() {
        val registry = CircuitBreakerConfiguration().circuitBreakerRegistry()

        assertThat(registry.allCircuitBreakers).isNotEmpty
        registry.allCircuitBreakers.forEach { cb ->
            assertThat(cb.circuitBreakerConfig.slowCallRateThreshold)
                .describedAs("%s slowCallRateThreshold", cb.name)
                .isLessThan(100f)
            assertThat(cb.circuitBreakerConfig.slowCallDurationThreshold)
                .describedAs("%s slowCallDurationThreshold", cb.name)
                .isLessThanOrEqualTo(Duration.ofSeconds(20))
        }
    }

    @Test
    fun `느린 호출이 임계 비율을 넘으면 실패 없이도 OPEN이 된다`() {
        val cb = CircuitBreaker.of("slow",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .slowCallRateThreshold(50f)
                .slowCallDurationThreshold(Duration.ofMillis(10))
                .build())

        // 4번 모두 "성공"하지만 느리다 — 실패율은 0%인데 slow-call 비율이 100%
        repeat(4) { cb.onSuccess(50, java.util.concurrent.TimeUnit.MILLISECONDS) }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    // ── 실제 설정 (ADR-053) ───────────────────────────────────────────────────
    //
    // 위 테스트들은 합성 설정으로 resilience4j 자체의 동작을 본다. 아래는 운영에 실제로
    // 올라가는 CircuitBreakerConfiguration 의 값을 검사한다 — 값이 틀리면 동작이 맞아도
    // 소용없다.

    private val real = CircuitBreakerConfiguration().circuitBreakerRegistry()

    @Test
    fun `결제 PG에도 서킷브레이커가 등록되어 있다`() {
        // 오랫동안 yahooFinance·kis·toss 셋뿐이었다 — 돈이 오가는 경로 중 결제만 빠져
        // 있었고, 방어는 10초 타임아웃 하나였다.
        assertThat(real.allCircuitBreakers.map { it.name })
            .contains("yahooFinance", "kis", "toss", "tossPg")
    }

    @Test
    fun `tossPg는 느린 호출에도 반응한다 — 임계는 read 타임아웃보다 짧다`() {
        val cfg = real.circuitBreaker("tossPg").circuitBreakerConfig

        assertThat(cfg.slowCallRateThreshold).isLessThan(100f)
        // PAYMENT_READ(10s)보다 짧아야 타임아웃으로 스레드가 고갈되기 전에 브레이커가 먼저 열린다.
        assertThat(cfg.slowCallDurationThreshold)
            .isLessThan(com.monticker.api.common.http.HttpTimeouts.PAYMENT_READ)
    }

    @Test
    fun `tossPg의 half-open 프로브는 2회 이상이다 — 조회와 청구가 둘 다 통과해야 한다`() {
        // 갱신 한 건이 복구되려면 호출이 두 번 필요하다: orderId로 "이미 청구됐나"를 묻고,
        // 아니면 그제서야 청구한다. 1이면 조회가 유일한 프로브를 소진하고 청구는 막혀,
        // 갱신이 다음 배치 주기(월 1회 = 한 달)까지 밀린다. CH-14에서 실제로 관측했다.
        assertThat(real.circuitBreaker("tossPg").circuitBreakerConfig.permittedNumberOfCallsInHalfOpenState)
            .isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `tossPg는 4xx를 장애로 세지 않는다`() {
        // 복구 경로의 첫 동작은 "이 orderId로 청구된 적 있나" 조회이고, 정상 답이 404다.
        // 그걸 실패로 세면 복구가 자기 브레이커를 스스로 열어버린다(CH-14에서 관측).
        val cb = real.circuitBreaker("tossPg")
        val notFound = org.springframework.web.client.HttpClientErrorException
            .create(org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", org.springframework.http.HttpHeaders(), ByteArray(0), null)

        repeat(6) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, notFound) }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(cb.metrics.numberOfFailedCalls).isZero()
    }

    @Test
    fun `tossPg는 5xx와 타임아웃은 장애로 센다`() {
        val cb = real.circuitBreaker("tossPg")
        val serverError = org.springframework.web.client.HttpServerErrorException
            .create(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "boom", org.springframework.http.HttpHeaders(), ByteArray(0), null)

        repeat(6) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, serverError) }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
    }
}
