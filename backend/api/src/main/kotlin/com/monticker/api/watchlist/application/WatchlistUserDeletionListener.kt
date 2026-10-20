package com.monticker.api.watchlist.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.search.SearchIndexEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-109 — 탈퇴하면 관심종목 그룹·항목을 지우고 검색 색인에서도 뺀다(색인 삭제도 같은 트랜잭션의 이벤트 — 롤백되면 함께 사라진다).
 * 그룹을 지우면 이 그룹을 대상으로 한 Watch Rule은 DB 트리거가 끈다(V92). 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class WatchlistUserDeletionListener(
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        val itemIds = jdbc.queryForList(
            """
            DELETE FROM watchlist_items
            WHERE group_id IN (SELECT id FROM watchlist_groups WHERE user_id = ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java, event.userId,
        )
        jdbc.update("DELETE FROM watchlist_groups WHERE user_id = ?", event.userId)
        itemIds.forEach { events.publishEvent(SearchIndexEvent.delete(WatchlistIndexer.INDEX, it.toString())) }
    }
}
