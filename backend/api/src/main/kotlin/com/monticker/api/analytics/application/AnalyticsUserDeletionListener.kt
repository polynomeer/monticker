package com.monticker.api.analytics.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-109 — 탈퇴하면 절세 시뮬레이션 기록과 포트폴리오 최적화 결과를 지운다. 다른 테이블이 참조하지 않는다.
 * 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class AnalyticsUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update("DELETE FROM tax_harvesting_logs WHERE user_id = ?", event.userId)
        jdbc.update("DELETE FROM portfolio_optimizations WHERE user_id = ?", event.userId)
    }
}
