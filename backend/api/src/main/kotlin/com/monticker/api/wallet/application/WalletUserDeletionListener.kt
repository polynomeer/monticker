package com.monticker.api.wallet.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.account.UserDeletionOrder
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-109 — 탈퇴하면 모의투자 원장·감정 태그·행동 점수·주간 리포트 발송 기록을 지운다(가상 자금, 보존 의무 없음).
 * order_emotion_tags가 paper_trades를 참조하므로 paper보다 먼저 돈다([UserDeletionOrder.WALLET]).
 * ledger_events.paper_trade_id는 FK가 없다(ADR-043). 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class WalletUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    @Order(UserDeletionOrder.WALLET)
    fun on(event: UserAccountDeletedEvent) {
        DELETE_SQL.forEach { jdbc.update(it, event.userId) }
    }

    companion object {
        val DELETE_SQL = listOf(
            "DELETE FROM order_emotion_tags WHERE user_id = ?",
            "DELETE FROM ledger_events WHERE user_id = ?",
            "DELETE FROM ledger_snapshots WHERE user_id = ?",
            "DELETE FROM investment_behavior_scores WHERE user_id = ?",
            "DELETE FROM weekly_report_sends WHERE user_id = ?",
        )
    }
}
