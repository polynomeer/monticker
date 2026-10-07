package com.monticker.api.marketdata.api

import com.monticker.api.common.calendar.MarketCalendar
import com.monticker.api.common.calendar.MarketSessionStatus
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * ADR-086 — 국내 장 상태와 KRX 휴장일 캘린더. 비로그인 공개(상단 바 장 상태가 쓴다).
 * 캘린더에 없는 해는 주말만 휴장으로 계산한다 — `calendarCovered`/`uncoveredYears`/`calendarCoverageUntil`로 그 사실을 함께 돌려준다.
 */
@RestController
@RequestMapping("/api/market")
class MarketCalendarController(private val calendar: MarketCalendar) {

    /** GET /api/market/status — OPEN/PRE/POST/CLOSED, 다음 개장·마감, 오늘 휴장일 이름 */
    @GetMapping("/status")
    fun status(): ResponseEntity<MarketStatusResponse> = ResponseEntity.ok(MarketStatusResponse.from(calendar.status()))

    /** GET /api/market/calendar?from=2026-10-01&to=2026-10-31 — 기간의 평일 휴장일과 영업일(최대 400일) */
    @GetMapping("/calendar")
    fun calendar(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<MarketCalendarResponse> {
        val start = from ?: calendar.today()
        val end = to ?: start.plusDays(30)
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) return ResponseEntity.badRequest().build()

        val snapshot = calendar.current()   // 한 응답 안에서는 같은 스냅샷으로 답한다
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        return ResponseEntity.ok(
            MarketCalendarResponse(
                market = "KRX",
                from = start,
                to = end,
                holidays = snapshot.holidaysBetween(start, end).map { HolidayResponse(it.date, it.name) },
                businessDays = days.filter { snapshot.isBusinessDay(it) },
                uncoveredYears = days.map { it.year }.distinct().filterNot { snapshot.isCovered(it) },
                calendarCoverageUntil = snapshot.coverageUntil(calendar.today()),
            )
        )
    }

    companion object {
        const val MAX_RANGE_DAYS = 400L
    }
}

data class MarketStatusResponse(
    val market: String,
    /** OPEN | PRE | POST | CLOSED */
    val status: String,
    val date: LocalDate,
    val isTradingDay: Boolean,
    val holidayName: String?,
    val openAt: OffsetDateTime?,
    val closeAt: OffsetDateTime?,
    val nextOpen: OffsetDateTime,
    val nextClose: OffsetDateTime,
    val calendarCovered: Boolean,
    val calendarCoverageUntil: LocalDate?,
) {
    companion object {
        fun from(s: MarketSessionStatus) = MarketStatusResponse(
            market = "KRX",
            status = s.phase.name,
            date = s.date,
            isTradingDay = s.isTradingDay,
            holidayName = s.holidayName,
            openAt = s.openAt?.toOffsetDateTime(),
            closeAt = s.closeAt?.toOffsetDateTime(),
            nextOpen = s.nextOpen.toOffsetDateTime(),
            nextClose = s.nextClose.toOffsetDateTime(),
            calendarCovered = s.calendarCovered,
            calendarCoverageUntil = s.calendarCoverageUntil,
        )
    }
}

data class HolidayResponse(val date: LocalDate, val name: String)

data class MarketCalendarResponse(
    val market: String,
    val from: LocalDate,
    val to: LocalDate,
    val holidays: List<HolidayResponse>,
    val businessDays: List<LocalDate>,
    /** 휴장일 데이터가 없는 해 — 이 해의 영업일은 주말만 뺀 값이다 */
    val uncoveredYears: List<Int>,
    val calendarCoverageUntil: LocalDate?,
)
