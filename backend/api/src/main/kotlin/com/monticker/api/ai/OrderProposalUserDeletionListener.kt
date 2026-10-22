package com.monticker.api.ai

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-114 — 탈퇴하면 AI 주문 제안을 지운다. 제안은 승인돼도 주문을 직접 내지 않는다 — 다른 테이블이 참조하지 않는다.
 * 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class OrderProposalUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update("DELETE FROM order_proposals WHERE user_id = ?", event.userId)
    }
}
