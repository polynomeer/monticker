package com.monticker.api.risk.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-109 — 탈퇴하면 리스크 한도 설정과 그 부속(대기 중인 완화 변경, 근접 경고 기록)을 지운다. 판정 기록(risk_check_logs)은 지우지 않는다 —
 * 실주문 기록과 같은 보존 대상이다(어떤 한도로 그 주문을 통과·거부했는가). 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class RiskUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update("DELETE FROM risk_limit_pending_changes WHERE user_id = ?", event.userId)
        jdbc.update("DELETE FROM risk_limit_warnings WHERE user_id = ?", event.userId)
        jdbc.update("DELETE FROM risk_limits WHERE user_id = ?", event.userId)
    }
}
