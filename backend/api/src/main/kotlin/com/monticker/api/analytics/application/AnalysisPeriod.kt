package com.monticker.api.analytics.application

import com.monticker.api.common.time.KstPeriod
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * /analytics 포트폴리오 분석 기간(ADR-097). 예전에는 "최근 400일 중 일봉 253개"로 고정이었다.
 *
 * - 프리셋 `3M`·`6M`·`1Y`(기본)·`2Y`: KST 오늘을 끝으로 그만큼 거슬러 올라간 구간.
 * - `CUSTOM`: `from`·`to`(KST 날짜, 양 끝 포함)를 함께 준다. `to`는 오늘 이후일 수 없고,
 *   구간은 [MIN_CUSTOM_DAYS]일 이상 [MAX_CUSTOM_YEARS]년 이하다 — 계산·DB 스캔 크기의 상한.
 *
 * 잘못된 값은 [IllegalArgumentException](→ 400).
 */
data class AnalysisPeriod(val label: String, val range: KstPeriod) {
    val from: LocalDate get() = range.from
    val to: LocalDate get() = range.to

    companion object {
        const val DEFAULT = "1Y"
        const val CUSTOM = "CUSTOM"
        const val MIN_CUSTOM_DAYS = 60L
        const val MAX_CUSTOM_YEARS = 3L

        private val PRESET_MONTHS = linkedMapOf("3M" to 3L, "6M" to 6L, "1Y" to 12L, "2Y" to 24L)
        val PRESETS: Set<String> get() = PRESET_MONTHS.keys

        fun resolve(period: String?, from: LocalDate?, to: LocalDate?, today: LocalDate = KstPeriod.today()): AnalysisPeriod {
            val label = (period?.trim()?.uppercase()?.ifEmpty { null })
                ?: if (from != null || to != null) CUSTOM else DEFAULT

            if (label != CUSTOM) {
                val months = PRESET_MONTHS[label]
                    ?: throw IllegalArgumentException("분석 기간은 ${PRESETS.joinToString("·")}·$CUSTOM 중 하나여야 합니다")
                require(from == null && to == null) { "from·to는 period=$CUSTOM 일 때만 지정할 수 있습니다" }
                return AnalysisPeriod(label, KstPeriod(today.minusMonths(months), today))
            }

            require(from != null && to != null) { "직접 지정 기간은 from과 to를 함께 지정해야 합니다" }
            require(from.isBefore(to)) { "from($from)은 to($to)보다 앞서야 합니다" }
            require(!to.isAfter(today)) { "to($to)는 오늘($today) 이후일 수 없습니다" }
            require(ChronoUnit.DAYS.between(from, to) >= MIN_CUSTOM_DAYS) { "분석 기간은 최소 ${MIN_CUSTOM_DAYS}일입니다" }
            require(!from.isBefore(to.minusYears(MAX_CUSTOM_YEARS))) { "분석 기간은 최대 ${MAX_CUSTOM_YEARS}년입니다" }
            return AnalysisPeriod(CUSTOM, KstPeriod(from, to))
        }
    }
}
