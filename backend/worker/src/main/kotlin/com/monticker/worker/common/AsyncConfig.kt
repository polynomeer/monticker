package com.monticker.worker.common

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.lang.reflect.Method
import java.util.concurrent.Executor

/**
 * Worker 모듈 @Async 설정.
 *
 * alertDispatchExecutor: 푸시 알림 Expo API 콜 전용 스레드풀.
 * @Scheduled(fixedDelay=5000) evaluate() 루프가 5초마다 실행되고,
 * 각 dispatchAlert는 독립 스레드에서 비동기로 전송되므로
 * 다음 evaluate() 사이클이 이전 전송 완료를 기다리지 않는다.
 */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

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

    /**
     * 기본 @Async 실행기 (ADR-042). Modulith 외부화 리스너(SearchIndexEvent → Kafka)가 여기서 돈다. 이름 붙은
     * 풀만 있으면 Spring이 SimpleAsyncTaskExecutor(스레드 상한 없음)로 폴백한다 — api에서 CH-05 브로커 정지 중
     * 체결마다 스레드가 늘던 그 결함. 유계 풀 + AbortPolicy: 거절돼도 event_publication에 미완료로 남아 재전송된다.
     */
    @Bean("moduleEventExecutor")
    fun moduleEventExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize    = 2
        maxPoolSize     = 8
        queueCapacity   = 1000
        setThreadNamePrefix("module-event-")
        setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    override fun getAsyncExecutor(): Executor = moduleEventExecutor()

    override fun getAsyncUncaughtExceptionHandler() =
        AsyncUncaughtExceptionHandler { ex, method, params ->
            log.error(
                "[Async/Worker] uncaught exception in {}.{}({}): {}",
                method.declaringClass.simpleName, method.name,
                params.joinToString(), ex.message, ex,
            )
        }
}
