package com.monticker.api.stock.api

import com.monticker.api.stock.domain.Stock

data class StockResponse(
    val id: Long,
    val symbol: String,
    val name: String,
    val market: String,
    val sector: String?,
    val currency: String,
    val isActive: Boolean,
) {
    companion object {
        fun from(stock: Stock) = StockResponse(
            id = stock.id,
            symbol = stock.symbol,
            name = stock.name,
            market = stock.market.name,
            sector = stock.sector,
            currency = stock.currency,
            isActive = stock.isActive,
        )
    }
}
