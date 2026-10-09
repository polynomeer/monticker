package com.monticker.api.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.auth.application.NotificationPreferenceService
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.exception.ExternalServiceUnavailableException
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-102 — 원클릭 수신 거부가 주간 리포트 발송 판정(WeeklyBehaviorReportJob 후보 SQL)이 보는 `weekly_report_email`만 끄는지,
 * 여러 번 불러도 같은지, 옛 Redis 설정(행 없음)을 덮지 않는지 실제 Postgres에서 확인한다.
 */
class WeeklyReportUnsubscribeIntegrationTest : PostgresIntegrationTest() {

    private fun service(legacy: Map<Long, String> = emptyMap(), redisDown: Boolean = false): NotificationPreferenceService {
        val redis = mockk<StringRedisTemplate>(relaxed = true) {
            every { opsForValue().get(any()) } answers {
                if (redisDown) throw RedisConnectionFailureException("down")
                legacy[firstArg<String>().removePrefix("notif:pref:").toLong()]
            }
        }
        return NotificationPreferenceService(jdbcTemplate, redis, jacksonObjectMapper(), mockk<ConsentService>(relaxed = true))
    }

    private fun newUser(deleted: Boolean = false): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname, deleted_at) VALUES (?, ?, ?) RETURNING id",
        Long::class.java, "unsub-${System.nanoTime()}@test.local", "unsub", if (deleted) Timestamp.from(Instant.now()) else null,
    )!!

    private fun row(userId: Long): Map<String, Any?>? =
        jdbcTemplate.queryForList("SELECT * FROM notification_preferences WHERE user_id = ?", userId).firstOrNull()

    @Test
    fun `turns off only the weekly report column and is idempotent`() {
        val u = newUser()
        jdbcTemplate.update(
            "INSERT INTO notification_preferences (user_id, fills_email, news_alert_email, weekly_report_email) VALUES (?, true, true, true)", u,
        )
        val svc = service()

        assertThat(svc.disableWeeklyReportEmail(u)).isTrue()
        val after = row(u)!!
        assertThat(after["weekly_report_email"]).isEqualTo(false)
        assertThat(after["fills_email"]).isEqualTo(true)
        assertThat(after["news_alert_email"]).isEqualTo(true)
        assertThat(after["all_enabled"]).isEqualTo(true)
        assertThat(after["email_enabled"]).isEqualTo(true)

        // 두 번째 — 이미 꺼져 있다. 값도 updated_at도 그대로
        assertThat(svc.disableWeeklyReportEmail(u)).isFalse()
        assertThat(row(u)!!["weekly_report_email"]).isEqualTo(false)
        assertThat(row(u)!!["updated_at"]).isEqualTo(after["updated_at"])
        assertThat(svc.get(u).weeklyReportEmail).isFalse()
    }

    @Test
    fun `a user without a row gets one built from their legacy settings, not defaults`() {
        val u = newUser()
        val svc = service(legacy = mapOf(u to """{"allEnabled":true,"pushEnabled":false,"fillsEmail":true}"""))

        assertThat(svc.disableWeeklyReportEmail(u)).isTrue()
        val created = row(u)!!
        assertThat(created["weekly_report_email"]).isEqualTo(false)
        assertThat(created["push_enabled"]).isEqualTo(false)   // 옛 설정이 살아 있다
        assertThat(created["fills_email"]).isEqualTo(true)
    }

    @Test
    fun `a user without row or legacy value gets defaults with the report off`() {
        val u = newUser()
        assertThat(service().disableWeeklyReportEmail(u)).isTrue()
        assertThat(row(u)!!["weekly_report_email"]).isEqualTo(false)
        assertThat(row(u)!!["push_enabled"]).isEqualTo(true)
    }

    @Test
    fun `legacy store down fails instead of overwriting other settings with defaults`() {
        val u = newUser()
        assertThatThrownBy { service(redisDown = true).disableWeeklyReportEmail(u) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
        assertThat(row(u)).isNull()
    }

    @Test
    fun `deleted and unknown users are a silent no-op`() {
        val deleted = newUser(deleted = true)
        assertThat(service().disableWeeklyReportEmail(deleted)).isFalse()
        assertThat(row(deleted)).isNull()
        assertThat(service().disableWeeklyReportEmail(Long.MAX_VALUE)).isFalse()
    }
}
