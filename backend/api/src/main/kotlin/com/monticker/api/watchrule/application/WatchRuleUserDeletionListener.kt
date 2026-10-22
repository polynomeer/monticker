package com.monticker.api.watchrule.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-114 — 탈퇴하면 Watch Rule을 지운다(발동 기록·일일 카운트는 FK CASCADE). 떠난 사용자 앞으로 모의 자동주문이 계속 나가지 않게 한다.
 * 이미 낸 모의 주문·체결은 모의투자 데이터 정리(후속)가 지운다. 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class WatchRuleUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update("DELETE FROM watch_rules WHERE user_id = ?", event.userId)
    }
}
