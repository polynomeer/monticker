package com.monticker.api.common.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * ADR-091 — KST 날짜 구간 `[from, to]`(양 끝 포함)와 그 경계 시각.
 *
 * 경계는 Kotlin에서 계산해 `Instant`로 바인딩한다. SQL의 `AT TIME ZONE`이나 서버 기본 시간대에 기대지 않는다 —
 * JVM이 UTC로 떠도(`-Duser.timezone=UTC`) 같은 결과가 나와야 한다.
 */
data class KstPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        require(!from.isAfter(to)) { "from($from)은 to($to)보다 늦을 수 없습니다" }
    }

    /** `from` 00:00 KST (포함) */
    val start: Instant get() = from.atStartOfDay(KST).toInstant()

    /** `to` 다음날 00:00 KST (제외) */
    val endExclusive: Instant get() = to.plusDays(1).atStartOfDay(KST).toInstant()

    val days: List<LocalDate> get() = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** 사용자가 고를 수 있는 가장 긴 구간: `to`로부터 1년(`from = to − 1년`까지 허용). */
        const val MAX_YEARS = 1L

        fun today(now: Instant = Instant.now()): LocalDate = now.atZone(KST).toLocalDate()

        /** 그 날짜의 00:00 KST */
        fun startOf(date: LocalDate): Instant = date.atStartOfDay(KST).toInstant()

        /** 그 날짜가 속한 KST 주(월~일)의 월요일. */
        fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** 그 날짜가 속한 KST 주(월요일 00:00 ~ 다음 월요일 00:00). */
        fun weekOf(date: LocalDate): KstPeriod = weekStart(date).let { KstPeriod(it, it.plusDays(6)) }

        /**
         * 요청 파라미터 검증. 둘 다 없으면 [defaultDays]일(오늘 포함), 하나만 있으면 거부한다.
         * `from > to`, 1년 초과 구간은 [IllegalArgumentException](→ 400).
         */
        fun parse(from: LocalDate?, to: LocalDate?, defaultDays: Long, now: Instant = Instant.now()): KstPeriod {
            require((from == null) == (to == null)) { "from과 to는 함께 지정해야 합니다" }
            if (from == null || to == null) {
                val t = today(now)
                return KstPeriod(t.minusDays(defaultDays - 1), t)
            }
            require(!from.isAfter(to)) { "from($from)은 to($to)보다 늦을 수 없습니다" }
            require(!from.isBefore(to.minusYears(MAX_YEARS))) { "조회 기간은 최대 ${MAX_YEARS}년입니다" }
            return KstPeriod(from, to)
        }
    }
}
