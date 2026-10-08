package com.monticker.api.auth.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.auth.api.NotificationPreferenceRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.consent.ConsentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class NotificationPreferenceServiceTest {
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val consents = mockk<ConsentService> { every { isAgreed(any(), ConsentType.MARKETING) } returns false }
    private val service = NotificationPreferenceService(jdbc, redis, jacksonObjectMapper(), consents)

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

        assertThat(service.get(7L)).isEqualTo(
            NotificationPreferenceRequest(quietHoursEnabled = false, quietHoursStart = "22:00", quietHoursEnd = "07:00"),
        )
        assertThat(NotificationPreferenceRequest().strategyMarketNewsPush).isFalse()
    }

    @Test
    fun `저장은 행을 upsert하고 옛 키를 지운다`() {
        noRow()
        service.save(7L, NotificationPreferenceRequest(allEnabled = false))

        verify { jdbc.update(match<String> { it.contains("ON CONFLICT (user_id)") }, *anyVararg()) }
        verify { redis.delete("notif:pref:7") }
    }

    // ── ADR-093 방해 금지 시간 ─────────────────────────────────────────────

    private fun noRow() {
        every { jdbc.query(any<String>(), any<RowMapper<NotificationPreferenceRequest>>(), 7L) } returns emptyList()
        every { redis.opsForValue().get(any()) } returns null
    }

    @Test
    fun `방해 금지 시간을 저장한다 - LocalTime으로 넘긴다`() {
        service.save(7L, NotificationPreferenceRequest(quietHoursEnabled = true, quietHoursStart = "23:30", quietHoursEnd = "06:00"))

        verify { jdbc.update(match<String> { it.contains("quiet_hours_start") }, *anyVararg(), true, LocalTime.of(23, 30), LocalTime.of(6, 0)) }
    }

    @Test
    fun `방해 금지 필드가 없는 예전 화면의 저장은 지금 값을 지우지 않는다`() {
        every { jdbc.query(any<String>(), any<RowMapper<NotificationPreferenceRequest>>(), 7L) } returns listOf(
            NotificationPreferenceRequest(quietHoursEnabled = true, quietHoursStart = "21:00", quietHoursEnd = "08:00"),
        )

        val saved = service.save(7L, NotificationPreferenceRequest(fillsPush = false))

        assertThat(saved.quietHoursEnabled).isTrue()
        assertThat(saved.quietHoursStart).isEqualTo("21:00")
        verify { jdbc.update(any<String>(), *anyVararg(), true, LocalTime.of(21, 0), LocalTime.of(8, 0)) }
    }

    @Test
    fun `시작과 종료가 같거나 형식이 틀리면 저장하지 않는다`() {
        for ((start, end) in listOf("22:00" to "22:00", "24:00" to "07:00", "7:00" to "08:00", "22:00:30" to "07:00", "ab:cd" to "07:00")) {
            assertThatThrownBy {
                service.save(7L, NotificationPreferenceRequest(quietHoursEnabled = true, quietHoursStart = start, quietHoursEnd = end))
            }.`as`("$start~$end").isInstanceOf(IllegalArgumentException::class.java)
        }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `전달 채널 - 끌 수 없는 종류는 방해 금지 시간에도 푸시, 끌 수 있는 종류는 설정대로`() {
        every { jdbc.query(any<String>(), any<RowMapper<NotificationPreferenceRequest>>(), 7L) } returns listOf(
            NotificationPreferenceRequest(priceAlertEmail = true, fillsPush = false, quietHoursEnabled = true, quietHoursStart = "22:00", quietHoursEnd = "07:00"),
        )
        // 2026-10-08 23:30 KST = 14:30 UTC — 자정 넘는 구간 안
        service.clock = Clock.fixed(Instant.parse("2026-10-08T14:30:00Z"), ZoneOffset.UTC)

        val res = service.channels(7L)
        val by = res.categories.associateBy { it.category }

        assertThat(res.quietHours.activeNow).isTrue()
        assertThat(res.quietHours.start).isEqualTo("22:00")
        assertThat(res.kakaoAvailable).isFalse()
        for (c in listOf("ORDER_OUTCOME", "CONDITIONAL_ORDER", "RISK_WARNING")) {
            assertThat(by.getValue(c).alwaysOn).isTrue()
            assertThat(by.getValue(c).push).isTrue()
            assertThat(by.getValue(c).pushDuringQuietHours).isTrue()
        }
        assertThat(by.getValue("PRICE_ALERT")).satisfies({
            assertThat(it.push).isTrue(); assertThat(it.email).isTrue(); assertThat(it.emailFallback).isFalse()
            assertThat(it.inApp).isTrue(); assertThat(it.pushDuringQuietHours).isFalse()
        })
        assertThat(by.getValue("FILLS").push).isFalse()
        // 마케팅 동의가 없으면 광고성은 어떤 채널로도 가지 않는다
        assertThat(by.getValue("STRATEGY_MARKET").push || by.getValue("STRATEGY_MARKET").email).isFalse()

        service.clock = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC)   // 12:00 KST
        assertThat(service.channels(7L).quietHours.activeNow).isFalse()
    }

    @Test
    fun `행이 없으면 방해 금지는 꺼짐 22시~07시`() {
        noRow()
        val res = service.channels(7L)
        assertThat(res.quietHours.enabled).isFalse()
        assertThat(res.quietHours.activeNow).isFalse()
        assertThat(res.quietHours.end).isEqualTo("07:00")
    }
}
