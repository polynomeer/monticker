package com.monticker.api.common.calendar

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class KrxCalendarTest {

    private val uncovered = mutableListOf<Int>()
    private val cal = V83Seed.calendar { uncovered += it }

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `seed has 17 closures in 2026 and 15 in 2027, all weekdays, unique`() {
        val byYear = V83Seed.holidays.groupBy { it.date.year }
        assertThat(byYear[2026]).hasSize(17)
        assertThat(byYear[2027]).hasSize(15)
        assertThat(V83Seed.holidays.map { it.date.dayOfWeek }).doesNotContain(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        assertThat(V83Seed.holidays.map { it.date }).doesNotHaveDuplicates()
        assertThat(V83Seed.years).containsExactlyInAnyOrder(2026, 2027)
    }

    @Test
    fun `holiday and substitute holiday are closed`() {
        assertThat(cal.isBusinessDay(d("2026-09-25"))).isFalse()   // 추석
        assertThat(cal.holidayName(d("2026-09-25"))).isEqualTo("추석")
        assertThat(cal.isBusinessDay(d("2026-10-05"))).isFalse()   // 개천절 대체공휴일(10/3 토)
        assertThat(cal.holidayName(d("2026-10-05"))).isEqualTo("개천절 대체공휴일")
        assertThat(cal.isBusinessDay(d("2027-02-09"))).isFalse()   // 설날 대체공휴일(연휴가 일요일과 겹침)
        assertThat(cal.isBusinessDay(d("2026-12-31"))).isFalse()   // 연말 휴장일
        assertThat(cal.isBusinessDay(d("2026-10-07"))).isTrue()
        assertThat(cal.holidayName(d("2026-10-07"))).isNull()
        assertThat(cal.holidayName(d("2026-10-03"))).isNull()       // 주말 공휴일은 행이 없다(주말 규칙으로 휴장)
        assertThat(cal.isBusinessDay(d("2026-10-03"))).isFalse()
        assertThat(uncovered).isEmpty()
    }

    @Test
    fun `T+2 skips weekend plus holiday chains`() {
        // 추석 전 영업일(9/23 수) 체결 → 9/24·25 추석, 9/26·27 주말 → 9/28(월) T+1, 9/29(화) T+2
        assertThat(cal.addBusinessDays(d("2026-09-23"), 2)).isEqualTo(d("2026-09-29"))
        // 10/2(금) 체결 → 10/3·4 주말, 10/5 개천절 대체 → 10/6 T+1, 10/7 T+2
        assertThat(cal.addBusinessDays(d("2026-10-02"), 2)).isEqualTo(d("2026-10-07"))
        // 10/8(목) → 10/9 한글날, 주말 → 10/12 T+1, 10/13 T+2
        assertThat(cal.addBusinessDays(d("2026-10-08"), 2)).isEqualTo(d("2026-10-13"))
        // 설 2027: 2/5(금) → 2/6~7 주말, 2/8 연휴, 2/9 대체 → 2/10 T+1, 2/11 T+2
        assertThat(cal.addBusinessDays(d("2027-02-05"), 2)).isEqualTo(d("2027-02-11"))
        assertThat(cal.addBusinessDays(d("2026-10-07"), 0)).isEqualTo(d("2026-10-07"))
        assertThatThrownBy { cal.addBusinessDays(d("2026-10-07"), -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `T+2 crosses the year boundary through the year-end closure and new year`() {
        // 12/29(화) → 12/30 T+1, 12/31 연말 휴장, 1/1 신정, 1/2·3 주말 → 2027-01-04 T+2
        assertThat(cal.addBusinessDays(d("2026-12-29"), 2)).isEqualTo(d("2027-01-04"))
        assertThat(cal.nextBusinessDay(d("2026-12-31"))).isEqualTo(d("2027-01-04"))
        assertThat(cal.previousBusinessDay(d("2027-01-03"))).isEqualTo(d("2026-12-30"))
        assertThat(cal.isFirstTradingDayOfYear(d("2027-01-04"))).isTrue()
        assertThat(cal.isFirstTradingDayOfYear(d("2026-01-02"))).isTrue()
        assertThat(cal.isFirstTradingDayOfYear(d("2026-01-05"))).isFalse()
    }

    @Test
    fun `settlement date uses the KST trade date and rolls non-business trade days forward`() {
        // 9/22(화) 23:30 UTC = 9/23(수) 08:30 KST → 체결일 9/23 → 9/29. UTC 날짜(9/22)로 셌다면 9/28이 된다.
        assertThat(cal.settlementDate(Instant.parse("2026-09-22T23:30:00Z"))).isEqualTo(d("2026-09-29"))
        // 토요일 체결(모의) → 월요일 체결로 → 수요일
        assertThat(cal.settlementDate(Instant.parse("2026-10-10T03:00:00Z"))).isEqualTo(d("2026-10-14"))
        // 추석 당일 체결 → 9/28(월) 체결로 → 9/30
        assertThat(cal.settlementDate(Instant.parse("2026-09-25T03:00:00Z"))).isEqualTo(d("2026-09-30"))
    }

    @Test
    fun `missing year falls back to weekends-only and reports it`() {
        // 2028은 캘린더에 없다 — 2028-01-03(월)은 실제로는 연초 첫 거래일이지만 공휴일 여부를 모르므로 영업일로 본다
        assertThat(cal.isCovered(2028)).isFalse()
        assertThat(cal.isBusinessDay(d("2028-01-03"))).isTrue()
        assertThat(cal.isBusinessDay(d("2028-01-01"))).isFalse()   // 토요일은 그래도 휴장
        assertThat(uncovered).contains(2028)
        // 2027-12-30(목) → 12/31 연말 휴장(커버) → 2028-01-03 T+1(미커버, 주말 규칙) → 01-04 T+2
        assertThat(cal.addBusinessDays(d("2027-12-30"), 2)).isEqualTo(d("2028-01-04"))
    }

    @Test
    fun `coverage until is the end of the contiguous covered run from today`() {
        assertThat(cal.coverageUntil(d("2026-10-07"))).isEqualTo(d("2027-12-31"))
        assertThat(cal.coverageUntil(d("2027-03-01"))).isEqualTo(d("2027-12-31"))
        assertThat(cal.coverageUntil(d("2028-01-01"))).isNull()
        assertThat(KrxCalendar(emptyList(), setOf(2026, 2028)).coverageUntil(d("2026-05-01"))).isEqualTo(d("2026-12-31"))
    }

    @Test
    fun `empty calendar is weekends-only and covers nothing`() {
        val seen = mutableListOf<Int>()
        val empty = KrxCalendar.empty { seen += it }
        assertThat(empty.isBusinessDay(d("2026-09-25"))).isTrue()
        assertThat(seen).containsExactly(2026)
        assertThat(empty.coverageUntil(d("2026-09-25"))).isNull()
    }

    @Test
    fun `holidays between is inclusive and ordered`() {
        assertThat(cal.holidaysBetween(d("2026-09-24"), d("2026-10-09")).map { it.date })
            .containsExactly(d("2026-09-24"), d("2026-09-25"), d("2026-10-05"), d("2026-10-09"))
    }
}
