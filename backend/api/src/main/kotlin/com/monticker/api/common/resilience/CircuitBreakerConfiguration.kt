package com.monticker.api.common.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
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

        // 상태 전이 이벤트 로깅
        listOf("tradingService", "quantEngine", "yahooFinance").forEach { name ->
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
}
