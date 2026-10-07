package com.monticker.api.common.redis

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

class WindowCounterTest {

    private val redis = mockk<StringRedisTemplate>()

    @Test
    fun `increments and sets the expiry in one script call`() {
        val script = slot<RedisScript<Long>>()
        val keys = slot<List<String>>()
        every { redis.execute(capture(script), capture(keys), "60") } returns 3L

        val count = WindowCounter.incrementInWindow(redis, "rate:api:1.2.3.4", Duration.ofMinutes(1))

        assertThat(count).isEqualTo(3L)
        assertThat(keys.captured).containsExactly("rate:api:1.2.3.4")
        // 만료 없는 키(TTL -1)도 다시 만료를 거는지 — 갇힌 키가 스스로 풀리는 조건
        assertThat(script.captured.scriptAsString).contains("INCR").contains("TTL").contains("< 0").contains("EXPIRE")
    }

    @Test
    fun `a sub-second window still expires`() {
        every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), "1") } returns 1L

        assertThat(WindowCounter.incrementInWindow(redis, "k", Duration.ofMillis(200))).isEqualTo(1L)
    }
}
