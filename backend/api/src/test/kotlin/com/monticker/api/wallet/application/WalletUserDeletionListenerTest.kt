package com.monticker.api.wallet.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.account.UserDeletionOrder
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate

class WalletUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)

    @Test
    fun `탈퇴하면 원장·감정 태그·행동 점수·주간 리포트 발송 기록을 지운다`() {
        WalletUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        listOf("order_emotion_tags", "ledger_events", "ledger_snapshots", "investment_behavior_scores", "weekly_report_sends")
            .forEach { table -> verify(exactly = 1) { jdbc.update("DELETE FROM $table WHERE user_id = ?", 42L) } }
    }

    @Test
    fun `paper_trades를 참조하므로 paper보다 먼저 돈다`() {
        val on = WalletUserDeletionListener::class.java.getMethod("on", UserAccountDeletedEvent::class.java)
        assertThat(on.getAnnotation(Order::class.java).value).isEqualTo(UserDeletionOrder.WALLET)
    }
}
