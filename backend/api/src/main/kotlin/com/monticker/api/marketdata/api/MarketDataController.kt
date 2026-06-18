package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.MarketDataService
import com.monticker.api.stock.application.StockService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/stocks")
class MarketDataController(
    private val marketDataService: MarketDataService,
    private val stockService: StockService,
) {
    @GetMapping("/{stockId}/price")
    fun getPrice(@PathVariable stockId: Long): ResponseEntity<PriceResponse> {
        val stock = try {
            stockService.getById(stockId)
        } catch (e: NoSuchElementException) {
            return ResponseEntity.notFound().build()
        }
        val tick = marketDataService.getLatestPrice(stockId, stock.symbol)
            ?: return ResponseEntity.ok(PriceResponse.noData(stockId, stock.symbol))
        return ResponseEntity.ok(PriceResponse.from(tick))
    }
}

data class PriceResponse(
    val stockId: Long,
    val symbol: String,
    val price: BigDecimal?,
    val volume: Long?,
    val tradeTime: Instant?,
    val hasData: Boolean,
) {
    companion object {
        fun from(tick: com.monticker.api.marketdata.domain.PriceTick) = PriceResponse(
            stockId = tick.stockId,
            symbol = tick.symbol,
            price = tick.price,
            volume = tick.volume,
            tradeTime = tick.tradeTime,
            hasData = true,
        )
        fun noData(stockId: Long, symbol: String) = PriceResponse(
            stockId = stockId,
            symbol = symbol,
            price = null,
            volume = null,
            tradeTime = null,
            hasData = false,
        )
    }
}
