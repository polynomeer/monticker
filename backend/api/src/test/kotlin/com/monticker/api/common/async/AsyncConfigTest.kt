package com.monticker.api.common.async

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

/**
 * CH-05에서 발견: 기본 @Async 실행기가 없으면 Spring은 SimpleAsyncTaskExecutor(스레드 상한 없음)로 폴백하고,
 * 모든 @ApplicationModuleListener가 거기서 돈다. 브로커 정지 중 체결마다 스레드가 하나씩 늘었다.
 */
class AsyncConfigTest {

    @Test
    fun `the default async executor is a bounded pool, not thread-per-task`() {
        val executor = AsyncConfig().getAsyncExecutor()

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor::class.java)
        val pool = executor as ThreadPoolTaskExecutor
        assertThat(pool.maxPoolSize).isLessThanOrEqualTo(32)
        assertThat(pool.threadNamePrefix).isEqualTo("module-event-")
        // 거절 시 CallerRuns가 아니어야 한다 — 커밋 직후 요청 스레드가 리스너를 대신 실행하며 매달리면 안 된다
        assertThat(pool.threadPoolExecutor.rejectedExecutionHandler).isInstanceOf(ThreadPoolExecutor.AbortPolicy::class.java)
    }
}
