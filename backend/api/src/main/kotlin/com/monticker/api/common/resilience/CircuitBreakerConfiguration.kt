package com.monticker.api.common.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * resilience-plan §B1 / P0-2 — 모든 서킷브레이커에 slowCallRateThreshold를 건다.
 *
 * failureRateThreshold만 있으면 외부가 "죽었을 때"만 열린다. 외부가 죽지 않고 느려지면
 * 호출은 (HttpTimeouts의 타임아웃까지) 정상 반환되므로 실패로 집계되지 않고, 브레이커는
 * 영원히 CLOSED다. 그 사이 Tomcat 스레드는 하나씩 응답을 기다리며 쌓인다.
 * slowCallDurationThreshold는 HttpTimeouts의 read 타임아웃보다 짧게 둔다 —
 * 타임아웃에 걸리기 전에 "느리다"로 먼저 집계돼야 브레이커가 스레드 고갈보다 먼저 열린다.
 */
@Configuration
class CircuitBreakerConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val registry = CircuitBreakerRegistry.ofDefaults()

        // Yahoo Finance 호가/캔들 API — 비공식, rate-limit 빈번
        registry.circuitBreaker("yahooFinance",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(60f)
                .slowCallRateThreshold(50f)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
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
                .slowCallRateThreshold(50f)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slidingWindowSize(6)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // ADR-026 — Toss Securities. 위 kis와 동일한 설정(브로커 실주문 API는 빠른 차단 우선).
        registry.circuitBreaker("toss",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slowCallRateThreshold(50f)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slidingWindowSize(6)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // 토스페이먼츠 PG (ADR-053) — 브로커와 같은 부류다(외부 HTTP, 돈이 움직임, 재시도가 위험).
        // 그런데 오래도록 타임아웃 하나만 달고 있었다: PG가 느려지면 PAYMENT_READ(10s)짜리
        // 요청이 Tomcat 스레드를 하나씩 물고 쌓이는데도 브레이커가 없어 아무도 못 막았다.
        //
        // 브로커(3s)보다 느린 5s를 slow 임계로 잡는다 — 카드사 경유 승인은 실제로 브로커보다
        // 느리다. 그래도 PAYMENT_READ(10s)보다는 짧아야 타임아웃으로 스레드가 고갈되기 전에
        // 브레이커가 먼저 열린다.
        //
        // waitDurationInOpenState가 브로커(30s)보다 긴 60s인 이유: 결제는 주문과 달리
        // 사용자가 초 단위로 재시도하지 않는다. 반쯤 죽은 PG를 성급히 찔러 불확정 상태
        // (INDETERMINATE)를 늘리는 것이, 1분 더 기다리는 것보다 훨씬 비싸다.
        registry.circuitBreaker("tossPg",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slowCallRateThreshold(50f)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slidingWindowSize(6)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(Exception::class.java)
                .build()
        )

        // 상태 전이 이벤트 로깅
        listOf("yahooFinance", "kis", "toss", "tossPg").forEach { name ->
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
