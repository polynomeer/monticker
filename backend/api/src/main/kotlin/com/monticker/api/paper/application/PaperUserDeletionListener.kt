package com.monticker.api.paper.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.account.UserDeletionOrder
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-109 — 탈퇴하면 모의 계좌와 그 기록을 지운다(가상 자금, 보존 의무 없음). 탈퇴 트랜잭션 안에서 동기로 돈다.
 *
 * 두 단계다([UserDeletionOrder]).
 * 1. [freeze] — 아직 발동하지 않은 조건부 주문을 취소한다. UPDATE가 행을 잠그므로 지금 발동 중인 주문(SKIP LOCKED로 잡힌 행)은
 *    끝날 때까지 기다리고, 그 뒤 스위퍼는 ACTIVE가 아닌 행을 건너뛴다.
 * 2. [delete] — wallet(감정 태그)이 paper_trades를 지운 뒤, matching(fills·orders)보다 먼저 지운다.
 *    paper_settlements → paper_trades → fills, paper_conditional_orders → orders FK 때문이다.
 */
@Component
class PaperUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    @Order(UserDeletionOrder.PAPER_FREEZE)
    fun freeze(event: UserAccountDeletedEvent) {
        jdbc.update(
            """
            UPDATE paper_conditional_orders SET status = 'CANCELLED', updated_at = now()
            WHERE user_id = ? AND status IN ('WAITING_PARENT', 'ACTIVE')
            """.trimIndent(),
            event.userId,
        )
    }

    @EventListener
    @Order(UserDeletionOrder.PAPER)
    fun delete(event: UserAccountDeletedEvent) {
        DELETE_SQL.forEach { jdbc.update(it, event.userId) }
    }

    companion object {
        /** 참조하는 쪽부터. portfolio_positions·paper_accounts는 다른 테이블이 참조하지 않는다. */
        val DELETE_SQL = listOf(
            "DELETE FROM paper_settlements WHERE user_id = ?",
            "DELETE FROM paper_trades WHERE user_id = ?",
            "DELETE FROM paper_conditional_orders WHERE user_id = ?",
            "DELETE FROM portfolio_positions WHERE user_id = ?",
            "DELETE FROM paper_accounts WHERE user_id = ?",
        )
    }
}
