package com.monticker.api.analytics.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class AnalyticsUserDeletionListenerTest {

    @Test
    fun `탈퇴하면 절세 시뮬레이션 기록과 포트폴리오 최적화 결과를 지운다`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)

        AnalyticsUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM tax_harvesting_logs WHERE user_id = ?", 42L) }
        verify(exactly = 1) { jdbc.update("DELETE FROM portfolio_optimizations WHERE user_id = ?", 42L) }
    }
}
