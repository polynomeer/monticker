package com.monticker.worker.marketdata

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant
import java.time.LocalDate

class KrxHolidayCalendarTest {

    private val chuseok = mapOf(
        LocalDate.of(2026, 9, 24) to "추석 연휴",
        LocalDate.of(2026, 9, 25) to "추석",
        LocalDate.of(2026, 10, 5) to "개천절 대체공휴일",
    )

    @AfterEach
    fun reset() {
        // MarketSchedule은 프로세스 전역 스냅샷이다 — 다른 테스트에 새지 않게 되돌린다
        MarketSchedule.krCalendar = KrxHolidayCalendar.weekendsOnly()
    }

    @Test
    fun `holidays and weekends are closed, missing year falls back to weekends-only`() {
        val uncovered = mutableListOf<Int>()
        val cal = KrxHolidayCalendar(chuseok, setOf(2026)) { uncovered += it }

        assertThat(cal.isBusinessDay(LocalDate.of(2026, 9, 25))).isFalse()
        assertThat(cal.holidayName(LocalDate.of(2026, 9, 25))).isEqualTo("추석")
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 9, 26))).isFalse()   // 토
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 9, 28))).isTrue()
        assertThat(uncovered).isEmpty()

        assertThat(cal.isBusinessDay(LocalDate.of(2028, 1, 3))).isTrue()     // 미커버 해 — 주말 규칙
        assertThat(uncovered).containsExactly(2028)
    }

    @Test
    fun `KR ticks are CLOSED all day on a KRX holiday and open on the next business day`() {
        MarketSchedule.krCalendar = KrxHolidayCalendar(chuseok, setOf(2026))
        val holiday10am = Instant.parse("2026-09-25T01:00:00Z")    // 10:00 KST 추석
        val next10am = Instant.parse("2026-09-28T01:00:00Z")       // 10:00 KST 월

        assertThat(MarketSchedule.getTickConfig("005930", "KOSPI", holiday10am).status).isEqualTo(MarketSchedule.MarketStatus.CLOSED)
        assertThat(MarketSchedule.getTickConfig("005930", "KOSPI", next10am).status).isEqualTo(MarketSchedule.MarketStatus.OPEN)
        // 미국 장은 한국 공휴일과 무관 (9/25 10:00 ET = 14:00 UTC)
        assertThat(MarketSchedule.getTickConfig("AAPL", "NASDAQ", Instant.parse("2026-09-25T14:00:00Z")).status).isEqualTo(MarketSchedule.MarketStatus.OPEN)
    }

    @Test
    fun `loader installs the DB snapshot and keeps it when a later load fails`() {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.query(match<String> { it.contains("FROM market_holidays") }, any<RowMapper<Pair<LocalDate, String>>>()) } returns
            chuseok.map { it.key to it.value }
        every { jdbc.queryForList(match<String> { it.contains("market_calendar_years") }, Int::class.java) } returns listOf(2026, 2027)
        val registry = SimpleMeterRegistry()
        val loader = KrxHolidayCalendarLoader(jdbc, registry)

        loader.load()
        assertThat(MarketSchedule.isKrBusinessDay(LocalDate.of(2026, 10, 5))).isFalse()
        assertThat(MarketSchedule.krCalendar.coveredYears).containsExactlyInAnyOrder(2026, 2027)

        every { jdbc.query(match<String> { it.contains("FROM market_holidays") }, any<RowMapper<Pair<LocalDate, String>>>()) } throws RuntimeException("db down")
        loader.refresh()
        assertThat(MarketSchedule.isKrBusinessDay(LocalDate.of(2026, 10, 5))).isFalse()

        // 미커버 해 조회는 메트릭으로 남는다
        MarketSchedule.isKrBusinessDay(LocalDate.of(2030, 3, 4))
        assertThat(registry.get("market_calendar_uncovered_lookups_total").tag("year", "2030").counter().count()).isEqualTo(1.0)
    }
}
