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

    override fun getAsyncUncaughtExceptionHandler() =
        AsyncUncaughtExceptionHandler { ex, method, params ->
            log.error(
                "[Async/Worker] uncaught exception in {}.{}({}): {}",
                method.declaringClass.simpleName, method.name,
                params.joinToString(), ex.message, ex,
            )
        }
}
