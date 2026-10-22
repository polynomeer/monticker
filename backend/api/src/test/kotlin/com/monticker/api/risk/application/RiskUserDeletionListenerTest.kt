package com.monticker.api.risk.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class RiskUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)

    @Test
    fun `탈퇴하면 한도 설정과 대기 중인 변경·근접 경고를 지운다`() {
        RiskUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM risk_limit_pending_changes WHERE user_id = ?", 42L) }
        verify(exactly = 1) { jdbc.update("DELETE FROM risk_limit_warnings WHERE user_id = ?", 42L) }
        verify(exactly = 1) { jdbc.update("DELETE FROM risk_limits WHERE user_id = ?", 42L) }
    }

    @Test
    fun `판정 기록은 실주문 기록과 함께 보존하므로 지우지 않는다`() {
        RiskUserDeletionListener(jdbc).on(UserAccountDeletedEvent(42L))

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("risk_check_logs") }, *anyVararg()) }
    }
}
