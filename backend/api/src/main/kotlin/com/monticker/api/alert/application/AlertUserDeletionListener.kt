package com.monticker.api.alert.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.search.SearchIndexEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-114 — 탈퇴하면 알림 규칙과 알림 이력을 지운다. 규칙의 소프트 삭제(ADR-073)와 달리 행을 지운다 — 되살릴 사용자가 없다.
 * 이력의 주인은 두 길이다(ADR-090): 규칙을 거쳐(rule_id) 또는 직접(user_id). 둘 다 지우고, 이력은 규칙보다 먼저 지운다(FK).
 * 검색 색인 삭제도 같은 트랜잭션의 이벤트다. 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class AlertUserDeletionListener(
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        val historyIds = jdbc.queryForList(
            """
            DELETE FROM alert_histories
            WHERE user_id = ? OR rule_id IN (SELECT id FROM alert_rules WHERE user_id = ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java, event.userId, event.userId,
        )
        jdbc.update("DELETE FROM alert_rules WHERE user_id = ?", event.userId)
        historyIds.forEach { events.publishEvent(SearchIndexEvent.delete(AlertHistoryIndexer.INDEX, it.toString())) }
    }
}
