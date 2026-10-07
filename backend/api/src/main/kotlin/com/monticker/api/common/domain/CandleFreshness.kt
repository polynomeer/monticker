package com.monticker.api.common.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 최신 1분봉 종가와 그 봉의 시각. */
data class LatestClose(val close: BigDecimal, val candleTime: Instant)

/**
 * 모의 체결·조건부 발동이 기준으로 삼는 1분봉의 신선도(보안 리뷰 2026-10). 시세 수집이 끊기거나 장이 닫힌 뒤의 마지막 봉
 * (몇 시간·며칠 전 값)으로 지정가를 체결하거나 손절을 발동하지 않는다 — 오래된 봉이면 그 주기는 건너뛴다.
 */
object CandleFreshness {
    val MAX_AGE: Duration = Duration.ofMinutes(5)

    /** SQL `interval` 리터럴 — [MAX_AGE]와 같은 값. */
    const val MAX_AGE_SQL = "5 minutes"

    fun isFresh(candleTime: Instant, now: Instant = Instant.now()): Boolean = !candleTime.isBefore(now.minus(MAX_AGE))
}
