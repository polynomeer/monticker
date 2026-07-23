package com.monticker.api.event.api

import com.monticker.api.event.application.EventSearchResult
import com.monticker.api.event.application.EventSearchService
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.application.SectorEventSummary
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.temporal.ChronoUnit

@Validated
@RestController
class EventTimelineController(
    private val eventTimelineService: EventTimelineService,
    private val eventSearchService: EventSearchService,
) {
    /**
     * 종목별 이벤트 타임라인.
     * query / types / minScore 지정 시 ES 전문검색, 미지정 시 DB 조회.
     *
     * GET /api/stocks/{stockId}/events
     * GET /api/stocks/{stockId}/events?query=어닝서프라이즈
     * GET /api/stocks/{stockId}/events?types=DISCLOSURE_PUBLISHED,NEWS_PUBLISHED&minScore=5
     */
    @GetMapping("/api/stocks/{stockId}/events")
    fun getTimeline(
        @PathVariable stockId: Long,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) types: List<String>?,
        @RequestParam(defaultValue = "0") minScore: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<List<StockEventResponse>> {
        val resolvedFrom = from ?: Instant.now().minus(24, ChronoUnit.HOURS)
        val resolvedTo   = to   ?: Instant.now()
        val results = eventSearchService.searchByStock(
            stockId    = stockId,
            query      = query,
            eventTypes = types ?: emptyList(),
            minScore   = minScore,
            from       = resolvedFrom,
            to         = resolvedTo,
            limit      = limit.coerceIn(1, 100),
        )
        return ResponseEntity.ok(results.map { StockEventResponse.from(it) })
    }

    /**
     * 전 종목 크로스 이벤트 검색.
     *
     * GET /api/events/search?query=금리인상
     * GET /api/events/search?query=AI&types=NEWS_PUBLISHED&minScore=7
     */
    @GetMapping("/api/events/search")
    fun searchAll(
        @RequestParam query: String,
        @RequestParam(required = false) types: List<String>?,
        @RequestParam(defaultValue = "0") minScore: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<List<StockEventResponse>> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        val results = eventSearchService.searchAll(
            query      = query,
            eventTypes = types ?: emptyList(),
            minScore   = minScore,
            from       = from,
            to         = to,
            limit      = limit.coerceIn(1, 100),
        )
        return ResponseEntity.ok(results.map { StockEventResponse.from(it) })
    }

    @GetMapping("/api/sectors/events")
    fun getSectorEvents(
        @RequestParam(defaultValue = "24") hours: Int,
    ): ResponseEntity<List<SectorEventSummary>> {
        val summary = eventTimelineService.getSectorSummary(hours.coerceIn(1, 72))
        return ResponseEntity.ok(summary)
    }

    @GetMapping("/api/events/recent")
    fun getRecentEvents(
        @RequestParam(defaultValue = "10") limit: Int,
    ): ResponseEntity<List<StockEventResponse>> {
        val events = eventTimelineService.getRecentEvents(limit.coerceIn(1, 50))
        return ResponseEntity.ok(events.map { StockEventResponse.from(it) })
    }
}
