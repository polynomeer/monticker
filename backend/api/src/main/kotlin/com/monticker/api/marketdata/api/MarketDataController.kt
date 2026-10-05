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
        val view = marketDataService.getPriceView(stockId, stock.symbol, stock.market.name)
            ?: return ResponseEntity.ok(PriceResponse.noData(stockId, stock.symbol))
        return ResponseEntity.ok(PriceResponse.from(view))
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
    /** 전 거래일 종가(KRX만). 모르면 null. */
    val prevClose: BigDecimal? = null,
    /** 전 거래일 대비 등락률(%, 소수 둘째 자리). prevClose가 없으면 null. */
    val changeRate: Double? = null,
) {
    companion object {
        fun from(view: com.monticker.api.marketdata.application.PriceView) = PriceResponse(
            stockId = view.tick.stockId,
            symbol = view.tick.symbol,
            price = view.tick.price,
            volume = view.tick.volume,
            tradeTime = view.tick.tradeTime,
            hasData = true,
            prevClose = view.prevClose,
            changeRate = view.changeRate,
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
