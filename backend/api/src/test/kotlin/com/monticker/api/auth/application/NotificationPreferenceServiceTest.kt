package com.monticker.api.auth.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.auth.api.NotificationPreferenceRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class NotificationPreferenceServiceTest {
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val service = NotificationPreferenceService(jdbc, redis, jacksonObjectMapper())

    @Test
    fun `행이 없으면 옛 Redis 값을 읽는다 - 모르는 필드가 빠져 있어도 기본값으로 채운다`() {
        every { jdbc.query(any<String>(), any<RowMapper<NotificationPreferenceRequest>>(), 7L) } returns emptyList()
        every { redis.opsForValue().get("notif:pref:7") } returns """{"pushEnabled":false,"priceAlertPush":false}"""

        val p = service.get(7L)

        assertThat(p.pushEnabled).isFalse()
        assertThat(p.priceAlertPush).isFalse()
        assertThat(p.allEnabled).isTrue()
        assertThat(p.strategyMarketNewsPush).isFalse()
    }

    @Test
    fun `행도 옛 값도 없으면 기본값 - 광고성 소식은 꺼져 있다`() {
        every { jdbc.query(any<String>(), any<RowMapper<NotificationPreferenceRequest>>(), 7L) } returns emptyList()
        every { redis.opsForValue().get(any()) } returns null

        assertThat(service.get(7L)).isEqualTo(NotificationPreferenceRequest())
        assertThat(NotificationPreferenceRequest().strategyMarketNewsPush).isFalse()
    }

    @Test
    fun `저장은 행을 upsert하고 옛 키를 지운다`() {
        service.save(7L, NotificationPreferenceRequest(allEnabled = false))

        verify { jdbc.update(match<String> { it.contains("ON CONFLICT (user_id)") }, *anyVararg()) }
        verify { redis.delete("notif:pref:7") }
    }
}
