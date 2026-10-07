package com.monticker.api.common.calendar

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MarketCalendarTest {

    private val repo = mockk<MarketHolidayRepository>()
    private val registry = SimpleMeterRegistry()
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)

    private fun calendar(now: String = "2026-10-07T01:00:00Z") = MarketCalendar(repo, registry, events).apply {
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC)
    }

    @Test
    fun `loads from the repository and answers from memory`() {
        every { repo.findHolidays() } returns V83Seed.holidays
        every { repo.findCoveredYears() } returns V83Seed.years
        val cal = calendar().also { it.load() }

        assertThat(cal.isBusinessDay(LocalDate.of(2026, 10, 9))).isFalse()
        assertThat(cal.addBusinessDays(LocalDate.of(2026, 9, 23), 2)).isEqualTo(LocalDate.of(2026, 9, 29))
        assertThat(cal.status().calendarCoverageUntil).isEqualTo(LocalDate.of(2027, 12, 31))
        assertThat(registry.get("market_calendar_coverage_years_ahead").gauge().value()).isEqualTo(1.0)
        verify(exactly = 1) { repo.findHolidays() }
        verify(exactly = 0) { events.publishEvent(any<Any>()) }   // 첫 로드는 변경 이벤트를 내지 않는다(기동 시 재정렬은 ApplicationReady가 한다)
    }

    @Test
    fun `refresh publishes a change event only when data changed`() {
        every { repo.findHolidays() } returns V83Seed.holidays
        every { repo.findCoveredYears() } returns V83Seed.years
        val cal = calendar().also { it.load() }

        assertThat(cal.refresh(publishChange = true)).isFalse()

        // 임시공휴일 추가
        every { repo.findHolidays() } returns V83Seed.holidays + MarketHoliday(LocalDate.of(2026, 10, 16), "임시공휴일", "TEMPORARY_HOLIDAY")
        assertThat(cal.refresh(publishChange = true)).isTrue()
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 10, 16))).isFalse()
        verify(exactly = 1) { events.publishEvent(any<MarketCalendarChangedEvent>()) }
    }

    @Test
    fun `load failure keeps the previous snapshot`() {
        every { repo.findHolidays() } returns V83Seed.holidays
        every { repo.findCoveredYears() } returns V83Seed.years
        val cal = calendar().also { it.load() }

        every { repo.findHolidays() } throws RuntimeException("db down")
        assertThat(cal.refresh(publishChange = true)).isFalse()
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 9, 25))).isFalse()
    }

    @Test
    fun `never loaded or missing year falls back to weekends-only with a metric`() {
        every { repo.findHolidays() } throws RuntimeException("db down")
        val cal = calendar().also { it.load() }

        assertThat(cal.isBusinessDay(LocalDate.of(2026, 9, 25))).isTrue()   // 모르면 주말만 휴장
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 9, 28))).isTrue()
        assertThat(registry.get("market_calendar_uncovered_lookups_total").tag("year", "2026").counter().count()).isEqualTo(2.0)
        assertThat(cal.status().calendarCovered).isFalse()
        assertThat(registry.get("market_calendar_coverage_years_ahead").gauge().value()).isEqualTo(-1.0)
    }
}
