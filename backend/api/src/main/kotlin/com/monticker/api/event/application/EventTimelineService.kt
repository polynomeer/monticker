package com.monticker.api.event.application

import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.StockEventRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class EventTimelineService(
    private val eventRepository: StockEventRepository,
) {
    fun getTimeline(
        stockId: Long,
        from: Instant = Instant.now().minus(24, ChronoUnit.HOURS),
        to: Instant = Instant.now(),
    ): List<StockEvent> =
        eventRepository.findByStockIdAndTimeRange(stockId, from, to)
}
