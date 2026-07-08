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
 * 두 개의 전용 스레드풀을 선언한다:
 *
 * behaviorScoreExecutor
 *   core=4, max=8, queue=200 — BehaviorScore 계산 (CPU 집약적 통계 연산)
 *   core가 4인 이유: 유저 수 × 1일 계산이므로 병렬도를 제한해 API 스레드풀에 영향 최소화
 *
 * alertDispatchExecutor
 *   core=2, max=16, queue=1000 — 푸시 알림 전송 (I/O 집약적, Expo API HTTP 콜)
 *   max=16으로 높게 설정해 알림 급증 시 처리량 확보; queue=1000으로 spike 흡수
 *
 * AsyncUncaughtExceptionHandler: 비동기 void 메서드 예외를 ERROR 레벨로 기록.
 */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

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
