package com.monticker.api.marketdata.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** ADR-071 — 지수·환율 최신 시세(market_index_quotes 한 행). */
data class MarketIndexQuote(
    val code: String,
    val name: String,
    val value: BigDecimal,
    val prevClose: BigDecimal?,
    val asOf: Instant,
    val source: String,
    val isMocked: Boolean,
) {
    val change: BigDecimal? get() = prevClose?.let { value.subtract(it) }

    /** 전일 대비 등락률(%). 전일 종가가 없으면 null — 0으로 꾸미지 않는다. */
    val changeRate: Double?
        get() = prevClose?.takeIf { it > BigDecimal.ZERO }?.let {
            value.subtract(it).divide(it, 8, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble()
        }
}

/** 지수 일별 종가(market_index_daily 한 행). */
data class MarketIndexClose(
    val date: LocalDate,
    val close: BigDecimal,
    val isMocked: Boolean,
)
