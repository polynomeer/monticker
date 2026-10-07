package com.monticker.api.common.calendar

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** 캘린더 데이터가 바뀌었다(기동 직후 첫 로드 제외). 정산일 재정렬이 이걸 듣는다. */
data class MarketCalendarChangedEvent(val coveredYears: Set<Int>)

/**
 * ADR-086 — api 쪽 KRX 거래일 캘린더. DB(market_holidays)를 메모리 스냅샷으로 들고 1시간마다 다시 읽는다.
 *
 * - 읽기 실패: 마지막 스냅샷을 유지한다(처음부터 못 읽었으면 빈 캘린더 = 주말만 휴장 + 경고).
 * - 캘린더에 없는 해를 물으면: 주말만 휴장으로 답하고, 해마다 1시간에 한 번 WARN 로그 +
 *   `market_calendar_uncovered_lookups_total{year}` 카운터. 조용히 "다 영업일"로 넘어가지 않게 한다.
 * - `market_calendar_coverage_years_ahead` 게이지: 올해부터 끊김 없이 채워진 해 수 - 1. 0이면 올해만, -1이면 올해도 없음.
 *   (알람 기준 예: 10월 이후 0 이하 → 내년 캘린더 마이그레이션 필요)
 */
@Component
class MarketCalendar(
    private val repository: MarketHolidayRepository,
    private val meterRegistry: MeterRegistry,
    private val events: ApplicationEventPublisher,
) : TradingCalendar {

    /** 테스트에서 바꾼다(RiskLimitService와 같은 관례). */
    internal var clock: Clock = Clock.systemUTC()

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastWarnAt = ConcurrentHashMap<Int, Instant>()
    private val snapshot = AtomicReference(KrxCalendar.empty(::onUncoveredYear))

    init {
        Gauge.builder("market_calendar_coverage_years_ahead", this) { cal ->
            val today = cal.today()
            cal.coverageUntil(today)?.let { (it.year - today.year).toDouble() } ?: -1.0
        }.description("올해부터 끊김 없이 채워진 KRX 휴장일 캘린더 해 수 - 1 (-1: 올해 데이터 없음)").register(meterRegistry)
    }

    @PostConstruct
    fun load() {
        refresh(publishChange = false)
    }

    @Scheduled(fixedDelayString = "\${market-calendar.refresh-ms:3600000}", initialDelayString = "\${market-calendar.refresh-ms:3600000}")
    fun scheduledRefresh() {
        refresh(publishChange = true)
    }

    /** DB에서 다시 읽는다. 데이터가 바뀌었으면 true. */
    fun refresh(publishChange: Boolean): Boolean {
        val loaded = try {
            KrxCalendar(repository.findHolidays(), repository.findCoveredYears(), ::onUncoveredYear)
        } catch (e: Exception) {
            log.warn("market calendar load failed — keeping previous snapshot (years={}): {}", snapshot.get().coveredYears, e.message)
            return false
        }
        val previous = snapshot.getAndSet(loaded)
        val changed = !previous.sameDataAs(loaded)
        if (changed) {
            log.info("market calendar loaded: years={} holidays={}", loaded.coveredYears.sorted(), loaded.holidays.size)
            if (!loaded.isCovered(today().year)) {
                log.warn("market calendar has no data for current year {} — business days fall back to weekends-only", today().year)
            }
            if (publishChange) events.publishEvent(MarketCalendarChangedEvent(loaded.coveredYears))
        }
        return changed
    }

    fun current(): KrxCalendar = snapshot.get()

    fun today(): LocalDate = LocalDate.now(clock.withZone(KrxCalendar.ZONE))

    fun status(now: Instant = clock.instant()): MarketSessionStatus = KrxSession.status(now, current())

    override fun holidayName(date: LocalDate) = current().holidayName(date)
    override fun isBusinessDay(date: LocalDate) = current().isBusinessDay(date)
    override fun isCovered(year: Int) = current().isCovered(year)
    override fun coverageUntil(today: LocalDate) = current().coverageUntil(today)
    override fun holidaysBetween(from: LocalDate, to: LocalDate) = current().holidaysBetween(from, to)
    override fun addBusinessDays(from: LocalDate, days: Int) = current().addBusinessDays(from, days)
    override fun nextBusinessDay(date: LocalDate) = current().nextBusinessDay(date)
    override fun previousBusinessDay(date: LocalDate) = current().previousBusinessDay(date)
    override fun settlementDate(tradedAt: Instant, days: Int) = current().settlementDate(tradedAt, days)
    override fun isFirstTradingDayOfYear(date: LocalDate) = current().isFirstTradingDayOfYear(date)

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
