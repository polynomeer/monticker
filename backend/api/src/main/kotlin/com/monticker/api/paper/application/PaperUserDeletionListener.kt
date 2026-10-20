package com.monticker.api.paper.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-109 — 탈퇴하면 아직 발동하지 않은 모의 조건부 주문을 취소한다. 떠난 사용자 앞으로 스위퍼가 주문을 내지 않게 하는 최소 조치다.
 * 모의 계좌·거래 자체의 삭제는 매칭 엔진(메모리 호가창)·원장과 FK로 엮여 있어 후속으로 따로 한다(ADR-109 Consequences).
 * 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class PaperUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update(
            """
            UPDATE paper_conditional_orders SET status = 'CANCELLED', updated_at = now()
            WHERE user_id = ? AND status IN ('WAITING_PARENT', 'ACTIVE')
            """.trimIndent(),
            event.userId,
        )
    }
}
