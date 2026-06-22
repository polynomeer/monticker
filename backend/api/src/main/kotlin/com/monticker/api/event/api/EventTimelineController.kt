package com.monticker.api.event.api

import com.monticker.api.event.application.EventTimelineService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
class EventTimelineController(
    private val eventTimelineService: EventTimelineService,
) {
    @GetMapping("/api/stocks/{stockId}/events")
    fun getTimeline(
        @PathVariable stockId: Long,
        @RequestParam(required = false) from: Instant? = null,
        @RequestParam(required = false) to: Instant? = null,
    ): ResponseEntity<List<StockEventResponse>> {
        val events = eventTimelineService.getTimeline(
            stockId = stockId,
            from = from ?: Instant.now().minusSeconds(86400),
            to = to ?: Instant.now(),
        ).map { StockEventResponse.from(it) }
        return ResponseEntity.ok(events)
    }

    @GetMapping("/api/events/recent")
    fun getRecentEvents(
        @RequestParam(defaultValue = "10") limit: Int,
    ): ResponseEntity<List<StockEventResponse>> {
        val events = eventTimelineService.getRecentEvents(limit.coerceIn(1, 50))
        return ResponseEntity.ok(events.map { StockEventResponse.from(it) })
    }
}
