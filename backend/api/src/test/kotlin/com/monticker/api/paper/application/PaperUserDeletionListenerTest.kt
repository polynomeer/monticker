package com.monticker.api.paper.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.account.UserDeletionOrder
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate

class PaperUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val listener = PaperUserDeletionListener(jdbc)

    @Test
    fun `freeze 단계는 아직 발동하지 않은 모의 조건부 주문만 취소한다`() {
        listener.freeze(UserAccountDeletedEvent(42L))

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

    @Test
    fun `delete 단계는 참조하는 쪽부터 모의 계좌 기록을 지운다`() {
        listener.delete(UserAccountDeletedEvent(42L))

        verifyOrder {
            jdbc.update("DELETE FROM paper_settlements WHERE user_id = ?", 42L)
            jdbc.update("DELETE FROM paper_trades WHERE user_id = ?", 42L)
            jdbc.update("DELETE FROM paper_conditional_orders WHERE user_id = ?", 42L)
            jdbc.update("DELETE FROM portfolio_positions WHERE user_id = ?", 42L)
            jdbc.update("DELETE FROM paper_accounts WHERE user_id = ?", 42L)
        }
    }

    @Test
    fun `freeze는 matching freeze보다 먼저, delete는 wallet 뒤 matching 앞에 돈다`() {
        val freeze = PaperUserDeletionListener::class.java.getMethod("freeze", UserAccountDeletedEvent::class.java)
        val delete = PaperUserDeletionListener::class.java.getMethod("delete", UserAccountDeletedEvent::class.java)
        assertThat(freeze.getAnnotation(Order::class.java).value).isEqualTo(UserDeletionOrder.PAPER_FREEZE)
        assertThat(delete.getAnnotation(Order::class.java).value).isEqualTo(UserDeletionOrder.PAPER)
        assertThat(UserDeletionOrder.PAPER_FREEZE).isLessThan(UserDeletionOrder.MATCHING_FREEZE)
        assertThat(UserDeletionOrder.WALLET).isLessThan(UserDeletionOrder.PAPER)
        assertThat(UserDeletionOrder.PAPER).isLessThan(UserDeletionOrder.MATCHING)
    }
}
