package com.monticker.worker.resilience

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * resilience-plan §B / P0-2 — 워커 CB도 느린 호출에 반응해야 한다.
 * resilience4j 기본 slowCallRateThreshold=100은 사실상 비활성이라, 새 CB를 추가하면서 빠뜨리면
 * 조용히 "죽었을 때만 열리는" 브레이커로 돌아간다. 전수 검사로 고정한다.
 */
class CircuitBreakerSlowCallTest {

    @Test
    fun `등록된 모든 CB는 slow-call 감지가 켜져 있다`() {
        val registry = CircuitBreakerConfiguration().circuitBreakerRegistry()

        assertThat(registry.allCircuitBreakers.map { it.name })
            .containsAll(listOf("kisApi", "expoPush", "naverNews", "dartApi"))
        registry.allCircuitBreakers.forEach { cb ->
            assertThat(cb.circuitBreakerConfig.slowCallRateThreshold)
                .describedAs("%s slowCallRateThreshold", cb.name).isLessThan(100f)
            assertThat(cb.circuitBreakerConfig.slowCallDurationThreshold)
                .describedAs("%s slowCallDurationThreshold", cb.name)
                .isLessThanOrEqualTo(Duration.ofSeconds(10))
        }
    }
}
