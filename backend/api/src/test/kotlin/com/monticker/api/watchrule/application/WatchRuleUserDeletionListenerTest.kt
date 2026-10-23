package com.monticker.api.watchrule.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/** 발동 기록·일일 카운트는 watch_rules ON DELETE CASCADE로 함께 지워진다(V49, V69) */
class WatchRuleUserDeletionListenerTest {

    @Test
    fun `탈퇴하면 Watch Rule을 지워 모의 자동주문이 더 나가지 않게 한다`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)

        WatchRuleUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM watch_rules WHERE user_id = ?", 42L) }
    }
}
