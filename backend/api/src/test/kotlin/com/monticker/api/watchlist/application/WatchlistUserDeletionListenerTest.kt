package com.monticker.api.watchlist.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.search.SearchIndexEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate

class WatchlistUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)

    @Test
    fun `탈퇴하면 관심종목 그룹·항목을 지우고 지운 항목을 색인에서도 뺀다`() {
        every { jdbc.queryForList(match<String> { it.contains("DELETE FROM watchlist_items") }, Long::class.java, 42L) } returns listOf(11L, 12L)

        WatchlistUserDeletionListener(jdbc, events).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { jdbc.update("DELETE FROM watchlist_groups WHERE user_id = ?", 42L) }
        verify { events.publishEvent(SearchIndexEvent.delete(WatchlistIndexer.INDEX, "11")) }
        verify { events.publishEvent(SearchIndexEvent.delete(WatchlistIndexer.INDEX, "12")) }
    }
}
