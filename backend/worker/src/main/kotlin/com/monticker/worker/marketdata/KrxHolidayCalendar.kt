package com.monticker.worker.marketdata

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-086 — worker 쪽 KRX 거래일 판단. 데이터의 단일 출처는 api가 마이그레이션으로 관리하는 market_holidays 테이블이고,
 * 이 클래스는 그 테이블의 읽기 전용 스냅샷이다(api `common.calendar.KrxCalendar`와 같은 규칙 — api와 worker는 별도
 * Gradle 프로젝트라 코드를 공유하지 못해 판단 규칙만 같은 모양으로 둔다. 바꿀 때 둘 다 바꾼다).
 *
 * 캘린더에 없는 해는 주말만 휴장으로 본다 — [onUncoveredYear]로 경고·메트릭을 남긴다.
 */
class KrxHolidayCalendar(
    private val holidays: Map<LocalDate, String>,
    val coveredYears: Set<Int>,
    private val onUncoveredYear: (Int) -> Unit = {},
) {
    fun holidayName(date: LocalDate): String? = holidays[date]

    fun isBusinessDay(date: LocalDate): Boolean {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        if (date.year !in coveredYears) {
            onUncoveredYear(date.year)
            return true
        }
        return date !in holidays
    }

    fun sameDataAs(other: KrxHolidayCalendar) = holidays == other.holidays && coveredYears == other.coveredYears

    val size: Int get() = holidays.size

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** DB를 못 읽었을 때 — 주말만 휴장 */
        fun weekendsOnly(onUncoveredYear: (Int) -> Unit = {}) = KrxHolidayCalendar(emptyMap(), emptySet(), onUncoveredYear)
    }
}

/**
 * market_holidays를 읽어 [MarketSchedule.krCalendar]에 꽂는다. 기동 시 한 번, 이후 1시간마다.
 * 읽기에 실패하면 이전 스냅샷을 유지한다(처음부터 실패면 주말만 휴장 + 경고).
 *
 * MarketSchedule은 여러 핸들러가 정적으로 부르는 object라, 캘린더를 생성자로 넘기는 대신 프로세스 전역 스냅샷으로 둔다.
 */
@Component
class KrxHolidayCalendarLoader(
    private val jdbc: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastWarnAt = ConcurrentHashMap<Int, Instant>()

    init {
        Gauge.builder("market_calendar_coverage_years_ahead", this) { loader ->
            val year = LocalDate.now(KrxHolidayCalendar.KST).year
            val years = MarketSchedule.krCalendar.coveredYears
            if (year !in years) -1.0 else { var y = year; while (y + 1 in years) y++; (y - year).toDouble() }
        }.description("올해부터 끊김 없이 채워진 KRX 휴장일 캘린더 해 수 - 1 (-1: 올해 데이터 없음)").register(meterRegistry)
    }

    @PostConstruct
    fun load() = refresh()

    @Scheduled(fixedDelayString = "\${market-calendar.refresh-ms:3600000}", initialDelayString = "\${market-calendar.refresh-ms:3600000}")
    fun refresh() {
        val loaded = try {
            val holidays = jdbc.query(
                "SELECT holiday_date, name FROM market_holidays WHERE market = 'KRX'",
            ) { rs, _ -> rs.getDate("holiday_date").toLocalDate() to rs.getString("name") }.toMap()
            val years = jdbc.queryForList("SELECT year FROM market_calendar_years WHERE market = 'KRX'", Int::class.java).toSet()
            KrxHolidayCalendar(holidays, years, ::onUncoveredYear)
        } catch (e: Exception) {
            log.warn("market calendar load failed — keeping previous snapshot (years={}): {}", MarketSchedule.krCalendar.coveredYears, e.message)
            if (MarketSchedule.krCalendar.coveredYears.isEmpty()) MarketSchedule.krCalendar = KrxHolidayCalendar.weekendsOnly(::onUncoveredYear)
            return
        }
        val previous = MarketSchedule.krCalendar
        MarketSchedule.krCalendar = loaded
        if (!previous.sameDataAs(loaded)) {
            log.info("market calendar loaded: years={} holidays={}", loaded.coveredYears.sorted(), loaded.size)
        }
    }

    private fun onUncoveredYear(year: Int) {
        meterRegistry.counter("market_calendar_uncovered_lookups_total", "year", year.toString()).increment()
        val now = Instant.now()
        val last = lastWarnAt[year]
        if (last == null || last.isBefore(now.minusSeconds(3600))) {
            lastWarnAt[year] = now
            log.warn("market calendar has no KRX holiday data for year {} — treating only weekends as closed. Add a migration (ADR-086).", year)
        }
    }
}
