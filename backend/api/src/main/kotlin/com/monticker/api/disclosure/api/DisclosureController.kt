package com.monticker.api.disclosure.api

import com.monticker.api.event.application.EventSearchService
import com.monticker.api.event.application.EventSearchResult
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 공시 전문검색 API.
 *
 * stock_events 인덱스의 DISCLOSURE_PUBLISHED 타입을 ES로 검색한다.
 *
 * GET /api/disclosures/search?query=사업보고서
 * GET /api/disclosures/search?query=유상증자&stockId=1&minScore=80
 * GET /api/stocks/{stockId}/disclosures?query=합병
 */
@RestController
class DisclosureController(private val eventSearchService: EventSearchService) {

    private val DISCLOSURE_TYPE = listOf("DISCLOSURE_PUBLISHED")

    /**
     * 전 종목 공시 키워드 검색.
     */
    @GetMapping("/api/disclosures/search")
    fun searchAll(
        @RequestParam query: String,
        @RequestParam(defaultValue = "0") minScore: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<List<DisclosureResponse>> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        val results = eventSearchService.searchAll(
            query      = query,
            eventTypes = DISCLOSURE_TYPE,
            minScore   = minScore,
            from       = from,
            to         = to,
            limit      = limit.coerceIn(1, 100),
        )
        return ResponseEntity.ok(results.map { DisclosureResponse.from(it) })
    }

    /**
     * 특정 종목 공시 검색.
     */
    @GetMapping("/api/stocks/{stockId}/disclosures")
    fun searchByStock(
        @PathVariable stockId: Long,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") minScore: Int,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<List<DisclosureResponse>> {
        val resolvedFrom = from ?: Instant.now().minusSeconds(90 * 24 * 3600L) // 기본 90일
        val resolvedTo   = to   ?: Instant.now()
        val results = eventSearchService.searchByStock(
            stockId    = stockId,
            query      = query,
            eventTypes = DISCLOSURE_TYPE,
            minScore   = minScore,
            from       = resolvedFrom,
            to         = resolvedTo,
            limit      = limit.coerceIn(1, 100),
        )
        return ResponseEntity.ok(results.map { DisclosureResponse.from(it) })
    }
}

data class DisclosureResponse(
    val id: Long,
    val stockId: Long,
    val title: String,
    val description: String?,
    val eventTime: Instant,
    val importanceScore: Int,
    val score: Float?,
) {
    companion object {
        fun from(r: EventSearchResult) = DisclosureResponse(
            id              = r.id,
            stockId         = r.stockId,
            title           = r.title,
            description     = r.description,
            eventTime       = r.eventTime,
            importanceScore = r.importanceScore,
            score           = r.score,
        )
    }
}
