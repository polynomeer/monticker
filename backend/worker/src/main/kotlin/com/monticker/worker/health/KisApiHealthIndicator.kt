package com.monticker.worker.health

import com.monticker.worker.kis.KisClient
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * KIS API 연결 상태와 CircuitBreaker 상태를 `/actuator/health` 에 노출한다.
 *
 * KIS API KEY가 설정되지 않은 경우 Mock 모드로 동작 중임을 명시하고 UP을 반환한다.
 * CircuitBreaker가 OPEN 상태이면 DOWN으로 표시해 이상 징후를 즉시 감지할 수 있다.
 */
@Component("kisApi")
class KisApiHealthIndicator(
    private val kisClient: KisClient,
    private val cbRegistry: CircuitBreakerRegistry,
) : HealthIndicator {

    override fun health(): Health {
        if (!kisClient.isConfigured) {
            return Health.up()
                .withDetail("mode", "mock — KIS API key not configured")
                .build()
        }

        val cb: CircuitBreaker? = runCatching { cbRegistry.circuitBreaker("kisApi") }.getOrNull()
        val cbState  = cb?.state?.name ?: "UNKNOWN"
        val metrics  = cb?.metrics

        return when (cb?.state) {
            CircuitBreaker.State.OPEN -> Health.down()
                .withDetail("circuitBreaker", cbState)
                .withMetric("failureRate", metrics?.failureRate)
                .withMetric("bufferedCalls", metrics?.numberOfBufferedCalls)
                .build()

            CircuitBreaker.State.HALF_OPEN -> Health.status("HALF_OPEN")
                .withDetail("circuitBreaker", cbState)
                .withMetric("permittedCalls", metrics?.numberOfNotPermittedCalls)
                .build()

            else -> Health.up()
                .withDetail("circuitBreaker", cbState)
                .withMetric("failureRate", metrics?.failureRate)
                .withMetric("successfulCalls", metrics?.numberOfSuccessfulCalls)
                .build()
        }
    }

    // 서킷 브레이커가 없으면(레지스트리 조회 실패) 지표가 null이다. withDetail은 null 값을 받지 않는다 — 그런 지표는 뺀다
    private fun Health.Builder.withMetric(key: String, value: Any?): Health.Builder =
        if (value == null) this else withDetail(key, value)
}
