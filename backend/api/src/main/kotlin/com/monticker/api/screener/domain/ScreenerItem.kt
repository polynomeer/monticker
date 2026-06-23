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
)
