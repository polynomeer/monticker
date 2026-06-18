package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.StockPriceProvider
import org.springframework.stereotype.Service

@Service
class MarketDataService(
    private val priceProvider: StockPriceProvider,
) {
    fun getLatestPrice(stockId: Long, symbol: String): PriceTick? =
        priceProvider.getLatestPrice(stockId, symbol)
}
