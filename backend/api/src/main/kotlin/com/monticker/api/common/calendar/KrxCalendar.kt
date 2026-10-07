package com.monticker.api.common.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-086 — KRX 거래일 판단. 영업일 계산은 반드시 이 인터페이스를 거친다(주말만 건너뛰는 사본을 만들지 않는다).
 * 날짜는 모두 KST 달력 날짜다. "오늘"이 필요하면 [KrxCalendar.ZONE]으로 구한다 — CI(UTC)와 개발 PC(KST)가 같은 답을 내야 한다.
 */
interface TradingCalendar {
    /** 평일 휴장일이면 이름(예: "추석"). 영업일이거나 주말이면 null. */
    fun holidayName(date: LocalDate): String?

    /** 장이 열리는 날인가. 캘린더에 없는 해는 주말만 휴장으로 본다(경고·메트릭을 남긴다). */
    fun isBusinessDay(date: LocalDate): Boolean

    /** 그 해의 휴장일을 캘린더가 알고 있는가 */
    fun isCovered(year: Int): Boolean

    /** [today]가 속한 해부터 끊김 없이 채워진 마지막 날(12/31). 올해가 비어 있으면 null. */
    fun coverageUntil(today: LocalDate): LocalDate?

    /** [from, to] 사이 평일 휴장일(오래된 순) */
    fun holidaysBetween(from: LocalDate, to: LocalDate): List<MarketHoliday>

    /** from 다음 날부터 영업일을 [days]개 센 날(T+n). days=0이면 from 그대로. */
    fun addBusinessDays(from: LocalDate, days: Int): LocalDate {
        require(days >= 0) { "days must be >= 0" }
        var date = from
        var remaining = days
        while (remaining > 0) {
            date = date.plusDays(1)
            if (isBusinessDay(date)) remaining--
        }
        return date
    }

    /** [date]가 영업일이면 그대로, 아니면 다음 영업일 */
    fun nextBusinessDay(date: LocalDate): LocalDate {
        var d = date
        while (!isBusinessDay(d)) d = d.plusDays(1)
        return d
    }

    /** [date]가 영업일이면 그대로, 아니면 이전 영업일 */
    fun previousBusinessDay(date: LocalDate): LocalDate {
        var d = date
        while (!isBusinessDay(d)) d = d.minusDays(1)
        return d
    }

    /**
     * 체결 시각 → 결제일(T+[days]). 체결일은 KST 날짜이고, 휴장일·주말 체결(모의투자는 장외에도 체결된다)은
     * 다음 영업일 체결로 본다 — 토요일 체결은 월요일 체결로 쳐서 수요일 결제다.
     */
    fun settlementDate(tradedAt: Instant, days: Int = 2): LocalDate =
        addBusinessDays(nextBusinessDay(tradedAt.atZone(KrxCalendar.ZONE).toLocalDate()), days)

    /** 그 해 첫 거래일 — KRX는 이날 개장식 때문에 1시간 늦게(10:00) 연다. */
    fun isFirstTradingDayOfYear(date: LocalDate): Boolean =
        isBusinessDay(date) && nextBusinessDay(LocalDate.of(date.year, 1, 1)) == date
}

data class MarketHoliday(val date: LocalDate, val name: String, val source: String)

/**
 * 불변 스냅샷. [MarketCalendar]가 DB에서 읽어 주기적으로 갈아 끼운다.
 *
 * @param coveredYears market_calendar_years에 있는 해. 여기 없는 해는 "모름" — 주말만 휴장으로 계산하고 [onUncoveredYear]를 부른다.
 */
class KrxCalendar(
    holidays: Collection<MarketHoliday>,
    val coveredYears: Set<Int>,
    private val onUncoveredYear: (Int) -> Unit = {},
) : TradingCalendar {

    private val byDate: Map<LocalDate, MarketHoliday> = holidays.associateBy { it.date }
    private val sorted: List<MarketHoliday> = holidays.sortedBy { it.date }

    val holidays: List<MarketHoliday> get() = sorted

    override fun holidayName(date: LocalDate): String? = byDate[date]?.name

    override fun isBusinessDay(date: LocalDate): Boolean {
        if (isWeekend(date)) return false
        if (date.year !in coveredYears) {
            onUncoveredYear(date.year)
            return true
        }
        return date !in byDate
    }

    override fun isCovered(year: Int): Boolean = year in coveredYears

    override fun coverageUntil(today: LocalDate): LocalDate? {
        if (today.year !in coveredYears) return null
        var y = today.year
        while (y + 1 in coveredYears) y++
        return LocalDate.of(y, 12, 31)
    }

    override fun holidaysBetween(from: LocalDate, to: LocalDate): List<MarketHoliday> =
        sorted.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    /** 같은 데이터인가 — 갱신 때 바뀐 게 있을 때만 정산일 재정렬을 돌린다. */
    fun sameDataAs(other: KrxCalendar): Boolean = coveredYears == other.coveredYears && sorted == other.sorted

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

        /** DB를 아직 못 읽었을 때 — 모든 해가 미커버(주말만 휴장). */
        fun empty(onUncoveredYear: (Int) -> Unit = {}) = KrxCalendar(emptyList(), emptySet(), onUncoveredYear)
    }
}
