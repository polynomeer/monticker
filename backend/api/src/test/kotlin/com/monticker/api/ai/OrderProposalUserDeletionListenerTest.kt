package com.monticker.api.ai

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class OrderProposalUserDeletionListenerTest {

    @Test
    fun `탈퇴하면 AI 주문 제안을 지운다`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)

        OrderProposalUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM order_proposals WHERE user_id = ?", 42L) }
    }
}
