package com.monticker.worker.marketdata

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
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

    /**
     * 올해부터 끊김 없이 채워진 해 수 - 1 (api `KrxCalendar.coverageUntil`과 같은 규칙). 0이면 올해만, -1이면 올해도 없음.
     * `market_calendar_coverage_years_ahead` 게이지 값이다.
     */
    fun coverageYearsAhead(today: LocalDate): Int {
        if (today.year !in coveredYears) return -1
        var y = today.year
        while (y + 1 in coveredYears) y++
        return y - today.year
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
 * 메트릭은 api `MarketCalendar`와 이름·의미가 같다 — 알람(infra/monitoring/alert-rules.yml의 MarketCalendar*)이 job별로 본다.
 *  - `market_calendar_coverage_years_ahead` 게이지: 이 프로세스가 들고 있는 스냅샷 기준([KrxHolidayCalendar.coverageYearsAhead]).
 *    DB에 행이 있어도 기동 때부터 읽지 못했으면 -1이다 — 그 worker는 지금 공휴일을 영업일로 센다.
 *  - `market_calendar_uncovered_lookups_total{year}` 카운터: 캘린더에 없는 해를 실제로 물었다(WARN 로그는 해마다 1시간에 1번).
 *
 * MarketSchedule은 여러 핸들러가 정적으로 부르는 object라, 캘린더를 생성자로 넘기는 대신 프로세스 전역 스냅샷으로 둔다.
 */
@Component
class KrxHolidayCalendarLoader(
    private val jdbc: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    /** 테스트에서 바꾼다(api MarketCalendar와 같은 관례). */
    internal var clock: Clock = Clock.systemUTC()

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastWarnAt = ConcurrentHashMap<Int, Instant>()

    init {
        Gauge.builder("market_calendar_coverage_years_ahead", this) { loader ->
            MarketSchedule.krCalendar.coverageYearsAhead(LocalDate.now(loader.clock.withZone(KrxHolidayCalendar.KST))).toDouble()
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
        val now = clock.instant()
        val last = lastWarnAt[year]
        if (last == null || last.isBefore(now.minusSeconds(3600))) {
            lastWarnAt[year] = now
            log.warn("market calendar has no KRX holiday data for year {} — treating only weekends as closed. Add a migration (ADR-086).", year)
        }
    }
}
