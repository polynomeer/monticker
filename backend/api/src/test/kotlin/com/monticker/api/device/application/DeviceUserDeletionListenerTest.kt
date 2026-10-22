package com.monticker.api.device.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class DeviceUserDeletionListenerTest {

    @Test
    fun `탈퇴하면 그 사용자의 푸시 토큰을 비활성화가 아니라 지운다`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)

        DeviceUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM device_tokens WHERE user_id = ?", 42L) }
    }
}
