package com.monticker.api.common.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class CircuitBreakerConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val registry = CircuitBreakerRegistry.ofDefaults()

        // MSA 내부 프록시 — trading-service
        // 다운 시 api 쓰레드 풀 고갈을 막는 것이 목적. 빠르게 OPEN 후 단일 프로세스(로컬) 폴백.
        registry.circuitBreaker("tradingService",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(6)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // MSA 내부 프록시 — quant-engine
        // backtest는 30초 타임아웃을 허용하므로 창을 더 보수적으로 설정.
        registry.circuitBreaker("quantEngine",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(4)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // Yahoo Finance 호가/캔들 API — 비공식, rate-limit 빈번
        registry.circuitBreaker("yahooFinance",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(60f)
                .slidingWindowSize(5)
                .waitDurationInOpenState(Duration.ofMinutes(2))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // 브로커 실주문 API (KIS/Toss) — 실계좌·실주문이 걸리므로 장애 시 스레드 풀 고갈보다
        // 빠른 차단이 우선. 신규 브로커 어댑터를 추가할 때도 이 이름 패턴을 따른다.
        registry.circuitBreaker("kis",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(6)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // 상태 전이 이벤트 로깅
        listOf("tradingService", "quantEngine", "yahooFinance", "kis").forEach { name ->
            registry.circuitBreaker(name).eventPublisher
                .onStateTransition { e ->
                    log.warn("[CircuitBreaker:{}] {} → {}",
                        name,
                        e.stateTransition.fromState,
                        e.stateTransition.toState)
                }
                .onCallNotPermitted {
                    log.debug("[CircuitBreaker:{}] 요청 차단됨 (OPEN 상태)", name)
                }
        }

        return registry
    }

    // resilience4j-micrometer 없이는 서킷브레이커 상태가 Prometheus에 전혀 노출되지 않는다 —
    // OPEN으로 전이돼도 로그에만 남고 알림은 못 건다. resilience4j_circuitbreaker_state 게이지로
    // 노출해 launch-plan.md Phase 3의 알림 규칙(alert-rules.yml)이 실제로 걸 수 있게 한다.
    @Bean
    fun circuitBreakerMetricsBinder(
        registry: CircuitBreakerRegistry,
        meterRegistry: MeterRegistry,
    ): TaggedCircuitBreakerMetrics =
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).also { it.bindTo(meterRegistry) }
}
