package com.monticker.api.screener.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class ScreenerUserDeletionListenerTest {

    @Test
    fun `탈퇴하면 저장한 스크리너 조건을 지운다`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)

        ScreenerUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM saved_screens WHERE user_id = ?", 42L) }
    }
}
