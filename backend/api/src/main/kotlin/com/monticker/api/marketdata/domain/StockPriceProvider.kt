package com.monticker.api.marketdata.domain

import java.math.BigDecimal
import java.time.Instant

data class PriceTick(
    val stockId: Long,
    val symbol: String,
    val price: BigDecimal,
    val volume: Long,
    val tradeTime: Instant,
)

interface StockPriceProvider {
    fun getLatestPrice(stockId: Long, symbol: String): PriceTick?
}
