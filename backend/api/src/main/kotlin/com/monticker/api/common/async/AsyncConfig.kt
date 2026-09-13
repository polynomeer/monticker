package com.monticker.api.common.async

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * @Async 전역 설정.
 *
 * 세 개의 전용 스레드풀을 선언한다 (Bulkhead 패턴 — 서로 격리):
 *
 * backtestExecutor
 *   core=2, max=4, queue=20 — CPU-heavy 백테스트 전용.
 *   queue 초과 시 RejectedExecutionException → BacktestController가 429로 변환.
 *
 * behaviorScoreExecutor
 *   core=4, max=8, queue=200 — BehaviorScore 계산 (CPU 집약적 통계 연산)
 *   core가 4인 이유: 유저 수 × 1일 계산이므로 병렬도를 제한해 API 스레드풀에 영향 최소화
 *
 * alertDispatchExecutor
 *   core=2, max=16, queue=1000 — 푸시 알림 전송 (I/O 집약적, Expo API HTTP 콜)
 *   max=16으로 높게 설정해 알림 급증 시 처리량 확보; queue=1000으로 spike 흡수
 *
 * conditionalOrderExecutor (ADR-032)
 *   core=2, max=8, queue=200 — 조건부 주문 평가·발동(브로커 API 호출 포함, I/O 집약적).
 *   alertDispatchExecutor보다 낮게 잡은 이유: 알림은 단순 푸시 전송이지만 이건 실제
 *   주문 제출까지 이어질 수 있어 무제한 병렬화보다 처리량을 의도적으로 제한한다.
 *
 * moduleEventExecutor (기본 @Async 실행기 — CH-05에서 추가)
 *   core=4, max=16, queue=1000 — 이름 없는 @Async, 즉 모든 @ApplicationModuleListener(원장 기록, Outbox
 *   Kafka 외부화, quant 리스너 …)가 여기서 돈다. 이전엔 기본 실행기가 없어 Spring이 SimpleAsyncTaskExecutor
 *   (요청마다 새 스레드, 상한 없음)로 폴백했다. Kafka 정지 실험에서 외부화 리스너가 send 퓨처를 delivery.timeout
 *   (120s)까지 붙들고 있어 체결마다 스레드가 하나씩 늘어났다 — 주문 부하 중 브로커가 죽으면 스레드 폭발이다.
 *   큐가 차면 AbortPolicy: 리스너 호출이 거절돼도 event_publication에 미완료로 남아 Outbox가 5분 뒤 재전송한다.
 *   CallerRunsPolicy를 쓰지 않는 이유: 커밋 직후의 요청 스레드가 리스너를 대신 실행하며 매달리게 된다.
 *
 * AsyncUncaughtExceptionHandler: 비동기 void 메서드 예외를 ERROR 레벨로 기록.
 */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean("backtestExecutor")
    fun backtestExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize    = 2
        maxPoolSize     = 4
        queueCapacity   = 20
        setThreadNamePrefix("backtest-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(60)
        initialize()
    }

    @Bean("behaviorScoreExecutor")
    fun behaviorScoreExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize    = 4
        maxPoolSize     = 8
        queueCapacity   = 200
        setThreadNamePrefix("behavior-score-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    @Bean("alertDispatchExecutor")
    fun alertDispatchExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize    = 2
        maxPoolSize     = 16
        queueCapacity   = 1000
        setThreadNamePrefix("alert-dispatch-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(10)
        initialize()
    }

    @Bean("conditionalOrderExecutor")
    fun conditionalOrderExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize    = 2
        maxPoolSize     = 8
        queueCapacity   = 200
        setThreadNamePrefix("conditional-order-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    @Bean("moduleEventExecutor")
    fun moduleEventExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize    = 4
        maxPoolSize     = 16
        queueCapacity   = 1000
        setThreadNamePrefix("module-event-")
        setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    override fun getAsyncExecutor(): Executor = moduleEventExecutor()

    override fun getAsyncUncaughtExceptionHandler() = AsyncUncaughtExceptionHandler { ex, method, params ->
        log.error(
            "[Async] uncaught exception in {}.{}({}): {}",
            method.declaringClass.simpleName,
            method.name,
            params.joinToString(),
            ex.message,
            ex,
        )
    }
}
