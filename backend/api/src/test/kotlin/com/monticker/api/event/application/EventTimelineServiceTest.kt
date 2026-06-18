package com.monticker.api.event.application

import com.monticker.api.event.domain.EventType
import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.StockEventRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EventTimelineServiceTest {

    private val repository = mockk<StockEventRepository>()
    private val service = EventTimelineService(repository)

    @Test
    fun `getTimeline returns events ordered by time`() {
        val now = Instant.now()
        val events = listOf(
            makeEvent(1L, EventType.VOLUME_SURGE, now),
            makeEvent(2L, EventType.PRICE_SPIKE, now.minusSeconds(60)),
        )
        every { repository.findByStockIdAndTimeRange(1L, any(), any()) } returns events

        val result = service.getTimeline(1L)

        assertThat(result).hasSize(2)
        assertThat(result[0].eventType).isEqualTo(EventType.VOLUME_SURGE)
    }

    @Test
    fun `getTimeline returns empty list when no events`() {
        every { repository.findByStockIdAndTimeRange(1L, any(), any()) } returns emptyList()

        val result = service.getTimeline(1L)

        assertThat(result).isEmpty()
    }

    private fun makeEvent(id: Long, type: EventType, time: Instant) = StockEvent(
        id = id,
        stockId = 1L,
        eventType = type,
        title = "test",
        eventTime = time,
        importanceScore = 50,
    )
}
