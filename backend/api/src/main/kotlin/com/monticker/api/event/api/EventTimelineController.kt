package com.monticker.api.event.api

import com.monticker.api.event.application.EventMarketContextService
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
    private val eventMarketContextService: EventMarketContextService,
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

    /**
     * 종목별 가장 최근 이벤트 1건씩(보유 종목 표 "최근 이벤트"). 종목마다 타임라인을 부르던 N+1 대신 한 번에.
     * 공개 시장 데이터라 비로그인도 읽을 수 있다(SecurityConfig의 GET /api/events 하위 permitAll). 한 번에 최대 [LATEST_MAX_STOCKS]종목.
     *
     * GET /api/events/latest?stockIds=1,2,3
     */
    @GetMapping("/api/events/latest")
    fun getLatestPerStock(
        @RequestParam stockIds: List<Long>,
    ): ResponseEntity<List<StockEventResponse>> {
        require(stockIds.size <= LATEST_MAX_STOCKS) { "stockIds는 최대 ${LATEST_MAX_STOCKS}개입니다" }
        val events = eventTimelineService.getLatestPerStock(stockIds)
        return ResponseEntity.ok(events.map { StockEventResponse.from(it) })
    }

    companion object {
        const val LATEST_MAX_STOCKS = 100
    }

    @GetMapping("/api/events/recent")
    fun getRecentEvents(
        @RequestParam(defaultValue = "10") limit: Int,
    ): ResponseEntity<List<StockEventResponse>> {
        val events = eventTimelineService.getRecentEvents(limit.coerceIn(1, 50))
        val context = eventMarketContextService.contextFor(events)
        return ResponseEntity.ok(events.map { StockEventResponse.from(it, context[it.id]) })
    }
}
