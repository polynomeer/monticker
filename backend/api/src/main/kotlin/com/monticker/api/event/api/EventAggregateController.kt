package com.monticker.api.event.api

import com.monticker.api.event.application.EventAggregateService
import com.monticker.api.event.application.EventTypeCount
import com.monticker.api.event.application.StockEventCount
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

/**
 * 이벤트 집계 API(ADR-087). 비로그인 공개(SecurityConfig의 /api/events 하위 GET 공개 규칙).
 * 잘못된 입력은 IllegalArgumentException → 400(GlobalExceptionHandler).
 */
@RestController
class EventAggregateController(private val service: EventAggregateService) {

    /**
     * 하루(KST) 이벤트 유형별 집계 — 홈 상단 "오늘 이벤트"·"급등·급락".
     *
     * GET /api/events/summary              (오늘, KST)
     * GET /api/events/summary?date=2026-10-06
     */
    @GetMapping("/api/events/summary")
    fun summary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ResponseEntity<EventSummaryResponse> {
        val d = EventAggregateService.resolveSummaryDate(date)
        val s = service.summary(d)
        return ResponseEntity.ok(
            EventSummaryResponse(
                date         = d,
                from         = EventAggregateService.dayStart(d),
                to           = EventAggregateService.dayStart(d.plusDays(1)),
                total        = s.total,
                byType       = s.byType,
                surgeStocks  = s.surgeStocks,
                plungeStocks = s.plungeStocks,
            ),
        )
    }

    /**
     * 종목별 기간 이벤트 수 — /compare "이벤트 수". 기간은 KST 기준 (오늘 − days)일 자정부터 오늘 끝까지.
     *
     * GET /api/events/counts?stockIds=1,2,3&days=180
     */
    @GetMapping("/api/events/counts")
    fun counts(
        @RequestParam stockIds: String,
        @RequestParam(defaultValue = "30") days: Int,
    ): ResponseEntity<EventCountsResponse> {
        val q = EventAggregateService.parseCountQuery(stockIds, days)
        return ResponseEntity.ok(EventCountsResponse(from = q.from, to = q.to, days = q.days, counts = service.counts(q)))
    }
}

data class EventSummaryResponse(
    /** KST 날짜 */
    val date: LocalDate,
    /** 집계 구간 [from, to) — 그날 KST 자정부터 다음 날 KST 자정 전까지 */
    val from: Instant,
    val to: Instant,
    val total: Long,
    /** 모든 EventType(0건 포함) */
    val byType: List<EventTypeCount>,
    /** 급등(PRICE_SPIKE) 이벤트가 난 종목 수 */
    val surgeStocks: Long,
    /** 급락(PRICE_DROP) 이벤트가 난 종목 수 */
    val plungeStocks: Long,
)

data class EventCountsResponse(
    val from: Instant,
    val to: Instant,
    val days: Int,
    /** 요청한 종목 전부(0건 포함), 종목 ID 오름차순 */
    val counts: List<StockEventCount>,
)
