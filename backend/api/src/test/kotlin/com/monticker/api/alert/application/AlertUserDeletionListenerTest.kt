package com.monticker.api.alert.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.search.SearchIndexEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate

class AlertUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)

    @Test
    fun `탈퇴하면 규칙을 거친 이력과 직접 주인인 이력을 규칙보다 먼저 지우고 색인에서도 뺀다`() {
        every { jdbc.queryForList(match<String> { it.contains("DELETE FROM alert_histories") }, Long::class.java, 42L, 42L) } returns listOf(5L, 6L)

        AlertUserDeletionListener(jdbc, events).on(UserAccountDeletedEvent(42L))

        verifyOrder {
            jdbc.queryForList(
                match<String> { it.contains("DELETE FROM alert_histories") && it.contains("user_id = ?") && it.contains("rule_id IN (SELECT id FROM alert_rules WHERE user_id = ?)") },
                Long::class.java, 42L, 42L,
            )
            jdbc.update("DELETE FROM alert_rules WHERE user_id = ?", 42L)
        }
        verify { events.publishEvent(SearchIndexEvent.delete(AlertHistoryIndexer.INDEX, "5")) }
        verify { events.publishEvent(SearchIndexEvent.delete(AlertHistoryIndexer.INDEX, "6")) }
    }
}
