package com.monticker.api.screener.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** ADR-114 — 탈퇴하면 저장한 스크리너 조건을 지운다. 사용자 행이 남는 소프트 삭제라 FK CASCADE가 돌지 않는다. 탈퇴 트랜잭션 안에서 동기로 돈다. */
@Component
class ScreenerUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update("DELETE FROM saved_screens WHERE user_id = ?", event.userId)
    }
}
