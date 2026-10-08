package com.monticker.api.marketdata.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 52주 최고/최저 — candles_1d의 [from](KST 날짜) 이후 일봉 고가 최대·저가 최소.
 *
 * 상장한 지 1년이 안 됐거나 수집을 늦게 시작해 일봉이 52주를 다 덮지 못하면 "있는 데이터만의" 고저를 그대로 주고,
 * 실제로 덮은 기간([firstDate]~[lastDate])을 함께 준다. 화면은 [fullPeriod]가 false면 "52주"라고 부르지 않고 기간을 적는다.
 */
data class PriceRange52w(
    val stockId: Long,
    val high: BigDecimal,
    val low: BigDecimal,
    /** 조회 구간의 시작(KST 오늘 - 52주) */
    val from: LocalDate,
    /** 구간 안 첫 일봉의 KST 날짜 */
    val firstDate: LocalDate,
    /** 구간 안 마지막 일봉의 KST 날짜 */
    val lastDate: LocalDate,
    /** 구간 안 일봉 수 */
    val tradingDays: Int,
) {
    /**
     * 일봉이 구간 시작부터 있었는가. 구간 시작일이 연휴·주말이면 첫 일봉은 며칠 뒤라서
     * [FULL_PERIOD_TOLERANCE_DAYS](설 연휴 + 주말을 덮는 길이)까지는 52주 전체로 본다.
     */
    val fullPeriod: Boolean get() = !firstDate.isAfter(from.plusDays(FULL_PERIOD_TOLERANCE_DAYS))

    companion object {
        const val FULL_PERIOD_TOLERANCE_DAYS = 10L
        const val WEEKS = 52L

        /** [today](KST 날짜) 기준 52주 구간의 시작 날짜 */
        fun windowStart(today: LocalDate): LocalDate = today.minusWeeks(WEEKS)
    }
}
