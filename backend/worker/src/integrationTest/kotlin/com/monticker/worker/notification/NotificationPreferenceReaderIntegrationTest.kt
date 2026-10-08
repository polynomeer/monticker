package com.monticker.worker.notification

import com.monticker.worker.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalTime

/**
 * ADR-093 / V90 — worker가 방해 금지 시간 컬럼을 실제 Postgres에서 읽는지, 그리고 V90 이전 스키마(api보다 worker를 먼저 배포한
 * 상태)에서도 깨지지 않고 꺼짐으로 읽는지.
 */
class NotificationPreferenceReaderIntegrationTest : PostgresIntegrationTest() {

    private val redis = mockk<StringRedisTemplate> { every { opsForValue().get(any()) } returns null }

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "quiet-${System.nanoTime()}@test.local", "quiet",
    )!!

    @Test
    fun `reads quiet hours written by the api`() {
        val userId = newUser()
        jdbcTemplate.update(
            """INSERT INTO notification_preferences (user_id, quiet_hours_enabled, quiet_hours_start, quiet_hours_end)
               VALUES (?, true, '23:30', '06:15')""",
            userId,
        )

        val pref = NotificationPreferenceReader(jdbcTemplate, redis).forUser(userId)

        assertThat(pref.quietHoursEnabled).isTrue()
        assertThat(pref.quietHoursStart).isEqualTo(LocalTime.of(23, 30))
        assertThat(pref.quietHoursEnd).isEqualTo(LocalTime.of(6, 15))
    }

    @Test
    fun `a row without quiet hours set reads the V90 defaults - off, 22 to 07`() {
        val userId = newUser()
        jdbcTemplate.update("INSERT INTO notification_preferences (user_id, price_alert_push) VALUES (?, false)", userId)

        val pref = NotificationPreferenceReader(jdbcTemplate, redis).forUser(userId)

        assertThat(pref.priceAlertPush).isFalse()
        assertThat(pref.quietHoursEnabled).isFalse()
        assertThat(pref.quietHoursStart).isEqualTo(LocalTime.of(22, 0))
        assertThat(pref.quietHoursEnd).isEqualTo(LocalTime.of(7, 0))
    }

    // worker는 api보다 먼저 배포된다 — V90이 아직 없는 스키마에서도 설정을 읽어야 한다(없으면 방해 금지 꺼짐).
    @Test
    fun `reads a pre-V90 table without the quiet hours columns as quiet hours off`() {
        val userId = newUser()
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS pre_v90")
        jdbcTemplate.execute("DROP TABLE IF EXISTS pre_v90.notification_preferences")
        jdbcTemplate.execute("CREATE TABLE pre_v90.notification_preferences (LIKE public.notification_preferences INCLUDING DEFAULTS)")
        jdbcTemplate.execute(
            "ALTER TABLE pre_v90.notification_preferences DROP COLUMN quiet_hours_enabled, DROP COLUMN quiet_hours_start, DROP COLUMN quiet_hours_end",
        )
        jdbcTemplate.update("INSERT INTO pre_v90.notification_preferences (user_id, fills_push) VALUES (?, false)", userId)
        val url = postgres.jdbcUrl + (if ('?' in postgres.jdbcUrl) "&" else "?") + "currentSchema=pre_v90,public"
        val oldSchema = JdbcTemplate(DriverManagerDataSource(url, postgres.username, postgres.password))

        val pref = NotificationPreferenceReader(oldSchema, redis).forUser(userId)

        assertThat(pref.fillsPush).isFalse()
        assertThat(pref.quietHoursEnabled).isFalse()
    }
}
