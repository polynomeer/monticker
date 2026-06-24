package com.monticker.worker.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
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

        // KIS API — 실 시세 연동. 실패 시 Mock으로 폴백.
        registry.circuitBreaker("kisApi",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)          // 실패율 50% 초과 시 OPEN
                .slidingWindowSize(10)               // 최근 10회 기준
                .waitDurationInOpenState(Duration.ofSeconds(30))  // 30초 후 HALF_OPEN
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // Expo Push API — 실패해도 비치명적. 더 관대하게 설정.
        registry.circuitBreaker("expoPush",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(60f)
                .slidingWindowSize(5)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // Naver News API — 5분 주기 수집. 실패 시 Mock 사용.
        registry.circuitBreaker("naverNews",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(4)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // 상태 전이 이벤트 로깅
        listOf("kisApi", "expoPush", "naverNews").forEach { name ->
            registry.circuitBreaker(name).eventPublisher
                .onStateTransition { e ->
                    log.warn("[CircuitBreaker:{}] {} → {}",
                        name,
                        e.stateTransition.fromState,
                        e.stateTransition.toState)
                }
                .onCallNotPermitted { _ ->
                    log.debug("[CircuitBreaker:{}] 요청 차단됨 (OPEN 상태)", name)
                }
        }

        return registry
    }
}
