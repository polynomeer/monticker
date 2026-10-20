package com.monticker.api.paper.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class PaperUserDeletionListenerTest {

    @Test
    fun `탈퇴하면 아직 발동하지 않은 모의 조건부 주문만 취소한다`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)

        PaperUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) {
            jdbc.update(
                match<String> {
                    it.contains("UPDATE paper_conditional_orders SET status = 'CANCELLED'") &&
                        it.contains("status IN ('WAITING_PARENT', 'ACTIVE')")
                },
                42L,
            )
        }
    }
}
