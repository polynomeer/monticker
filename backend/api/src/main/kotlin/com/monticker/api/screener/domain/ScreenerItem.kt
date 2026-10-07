package com.monticker.api.screener.domain

data class ScreenerItem(
    val rank: Int,
    val stockId: Long,
    val symbol: String,
    val name: String,
    val market: String,
    val sector: String?,
    val price: java.math.BigDecimal,
    val prevClose: java.math.BigDecimal?,
    val changeRate: Double,           // %
    val changeAmount: java.math.BigDecimal,
    val volume: Long,
    val amount: java.math.BigDecimal, // 거래대금
    val buyRatio: Int,                // Mock: 40~70
    val sellRatio: Int,               // 100 - buyRatio
    val marketCap: Long?,             // 시가총액 (원 단위) — KOSPI/KOSDAQ만 존재
    val per: java.math.BigDecimal?,
    val pbr: java.math.BigDecimal?,
    val isFundamentalsMocked: Boolean,
    /** 최신 일봉 거래량 ÷ 직전 20거래일 평균(ADR-072). 일봉이 모자라면 null */
    val volumeMultiple: Double? = null,
    /** 오늘(KST) 이 종목에 생긴 stock_events 유형 */
    val todayEvents: List<String> = emptyList(),
)
