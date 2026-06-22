package com.monticker.api.event.api

import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.application.SectorEventSummary
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

    @GetMapping("/api/sectors/events")
    fun getSectorEvents(
        @RequestParam(defaultValue = "24") hours: Int,
    ): ResponseEntity<List<SectorEventSummary>> {
        val summary = eventTimelineService.getSectorSummary(hours.coerceIn(1, 72))
        return ResponseEntity.ok(summary)
    }
}
