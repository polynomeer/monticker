package com.monticker.api.common.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** ADR-086 — 국내 정규장 기준 장 상태. 시간외·NXT 세션은 구분하지 않는다(PRE/POST로 뭉뚱그린다). */
enum class MarketPhase { PRE, OPEN, POST, CLOSED }

data class MarketSessionStatus(
    val date: LocalDate,
    val phase: MarketPhase,
    val isTradingDay: Boolean,
    /** 오늘이 평일 휴장일이면 이름 */
    val holidayName: String?,
    /** 오늘 정규장 시각(휴장일·주말이면 null) */
    val openAt: ZonedDateTime?,
    val closeAt: ZonedDateTime?,
    val nextOpen: ZonedDateTime,
    val nextClose: ZonedDateTime,
    /** 오늘이 속한 해의 휴장일을 캘린더가 아는가. false면 휴장일을 놓쳤을 수 있다. */
    val calendarCovered: Boolean,
    val calendarCoverageUntil: LocalDate?,
)

/**
 * KRX 정규장 시각 규칙.
 *  - 정규장 09:00–15:30, 장전(동시호가·시간외) 08:30–09:00, 장후(시간외) 15:30–18:00.
 *  - 그 해 첫 거래일은 개장식으로 10:00 개장(종료는 같다).
 *  - 수능일 등 당국이 따로 공지하는 시간 변경은 다루지 않는다(ADR-086 범위 밖).
 */
object KrxSession {
    val PRE_START: LocalTime = LocalTime.of(8, 30)
    val OPEN: LocalTime = LocalTime.of(9, 0)
    val FIRST_DAY_OPEN: LocalTime = LocalTime.of(10, 0)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    val POST_END: LocalTime = LocalTime.of(18, 0)

    fun openTime(date: LocalDate, calendar: TradingCalendar): LocalTime =
        if (calendar.isFirstTradingDayOfYear(date)) FIRST_DAY_OPEN else OPEN

    fun status(now: Instant, calendar: TradingCalendar): MarketSessionStatus {
        val local = now.atZone(KrxCalendar.ZONE)
        val today = local.toLocalDate()
        val t = local.toLocalTime()
        val trading = calendar.isBusinessDay(today)
        val open = if (trading) openTime(today, calendar) else null

        val phase = when {
            !trading || open == null -> MarketPhase.CLOSED
            t < PRE_START -> MarketPhase.CLOSED
            t < open -> MarketPhase.PRE
            t < CLOSE -> MarketPhase.OPEN
            t < POST_END -> MarketPhase.POST
            else -> MarketPhase.CLOSED
        }

        // 다음 개장: 오늘 개장 전이면 오늘, 아니면 다음 영업일. 다음 마감: 오늘 마감 전이면 오늘, 아니면 다음 영업일.
        val nextOpenDate = if (trading && t < open!!) today else calendar.nextBusinessDay(today.plusDays(1))
        val nextCloseDate = if (trading && t < CLOSE) today else calendar.nextBusinessDay(today.plusDays(1))

        return MarketSessionStatus(
            date = today,
            phase = phase,
            isTradingDay = trading,
            holidayName = calendar.holidayName(today),
            openAt = open?.let { today.atTime(it).atZone(KrxCalendar.ZONE) },
            closeAt = if (trading) today.atTime(CLOSE).atZone(KrxCalendar.ZONE) else null,
            nextOpen = nextOpenDate.atTime(openTime(nextOpenDate, calendar)).atZone(KrxCalendar.ZONE),
            nextClose = nextCloseDate.atTime(CLOSE).atZone(KrxCalendar.ZONE),
            calendarCovered = calendar.isCovered(today.year),
            calendarCoverageUntil = calendar.coverageUntil(today),
        )
    }
}
