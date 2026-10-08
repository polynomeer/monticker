package com.monticker.api.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.auth.api.NotificationPreferenceRequest
import com.monticker.api.auth.application.NotificationPreferenceService
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.LocalTime

/** ADR-093 / V90 — 방해 금지 시간 컬럼·기본값·CHECK와 저장·조회 왕복을 실제 Postgres에서 확인한다. */
class NotificationQuietHoursIntegrationTest : PostgresIntegrationTest() {

    private val redis = mockk<StringRedisTemplate>(relaxed = true) { every { opsForValue().get(any()) } returns null }
    private val service = NotificationPreferenceService(jdbcTemplate, redis, jacksonObjectMapper(), mockk<ConsentService>(relaxed = true))

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "quiet-${System.nanoTime()}@test.local", "quiet",
    )!!

    @Test
    fun `rows written before V90 get quiet hours off, 22 to 07`() {
        val userId = newUser()
        jdbcTemplate.update("INSERT INTO notification_preferences (user_id) VALUES (?)", userId)

        val row = jdbcTemplate.queryForMap(
            "SELECT quiet_hours_enabled, quiet_hours_start, quiet_hours_end FROM notification_preferences WHERE user_id = ?", userId,
        )
        assertThat(row["quiet_hours_enabled"]).isEqualTo(false)
        assertThat(service.get(userId).quietHoursStart).isEqualTo("22:00")
        assertThat(service.get(userId).quietHoursEnd).isEqualTo("07:00")
    }

    @Test
    fun `save and read back a window that crosses midnight`() {
        val userId = newUser()
        service.save(userId, NotificationPreferenceRequest(quietHoursEnabled = true, quietHoursStart = "23:30", quietHoursEnd = "06:15"))

        val read = service.get(userId)
        assertThat(read.quietHoursEnabled).isTrue()
        assertThat(read.quietHoursStart).isEqualTo("23:30")
        assertThat(read.quietHoursEnd).isEqualTo("06:15")
        assertThat(jdbcTemplate.queryForObject("SELECT quiet_hours_start FROM notification_preferences WHERE user_id = ?", LocalTime::class.java, userId))
            .isEqualTo(LocalTime.of(23, 30))
    }

    @Test
    fun `a save without quiet hours fields keeps the stored window`() {
        val userId = newUser()
        service.save(userId, NotificationPreferenceRequest(quietHoursEnabled = true, quietHoursStart = "21:00", quietHoursEnd = "08:00"))

        service.save(userId, NotificationPreferenceRequest(fillsPush = false))   // 예전 화면 — 방해 금지 필드를 모른다

        val read = service.get(userId)
        assertThat(read.fillsPush).isFalse()
        assertThat(read.quietHoursEnabled).isTrue()
        assertThat(read.quietHoursStart).isEqualTo("21:00")
    }

    @Test
    fun `the check constraint rejects equal start and end and sub-minute times written directly`() {
        val userId = newUser()
        assertThatThrownBy {
            jdbcTemplate.update("INSERT INTO notification_preferences (user_id, quiet_hours_start, quiet_hours_end) VALUES (?, '22:00', '22:00')", userId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update("INSERT INTO notification_preferences (user_id, quiet_hours_start) VALUES (?, '22:00:30')", userId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
