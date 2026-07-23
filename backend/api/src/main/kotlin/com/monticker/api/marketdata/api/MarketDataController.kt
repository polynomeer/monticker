package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.MarketDataService
import com.monticker.api.marketdata.application.VwapService
import com.monticker.api.marketdata.application.VwapResponse
import com.monticker.api.marketdata.application.VwapPoint
import com.monticker.api.stock.application.StockService
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@Validated
@RestController
@RequestMapping("/api/stocks")
class MarketDataController(
    private val marketDataService: MarketDataService,
    private val stockService: StockService,
    private val vwapService: VwapService,
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

    @GetMapping("/{stockId}/vwap")
    fun getVwap(@PathVariable stockId: Long): ResponseEntity<VwapResponse> =
        ResponseEntity.ok(vwapService.getDailyVwap(stockId))

    @GetMapping("/{stockId}/vwap/series")
    fun getVwapSeries(@PathVariable stockId: Long): ResponseEntity<List<VwapPoint>> =
        ResponseEntity.ok(vwapService.getVwapSeries(stockId))
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
