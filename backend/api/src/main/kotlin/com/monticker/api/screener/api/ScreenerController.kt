package com.monticker.api.screener.api

import com.monticker.api.screener.application.ScreenerService
import com.monticker.api.screener.domain.ScreenerItem
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/screener")
class ScreenerController(private val screenerService: ScreenerService) {

    @GetMapping
    fun getScreener(
        @RequestParam(defaultValue = "realtime") tab: String,
        @RequestParam(defaultValue = "all")      market: String,
        @RequestParam(defaultValue = "amount")   sort: String,
        @RequestParam(defaultValue = "20")       limit: Int,
        @RequestParam(defaultValue = "0")        offset: Int,
    ): ResponseEntity<ScreenerResponse> {
        val result = screenerService.getItems(tab, market, sort, limit, offset)
        return ResponseEntity.ok(
            ScreenerResponse(
                items     = result.items.map { ScreenerItemResponse.from(it) },
                total     = result.total,
                hasMore   = result.hasMore,
                updatedAt = Instant.now(),
            )
        )
    }
}

data class ScreenerResponse(
    val items: List<ScreenerItemResponse>,
    val total: Int,
    val hasMore: Boolean,
    val updatedAt: Instant,
)

data class ScreenerItemResponse(
    val rank: Int,
    val stockId: Long,
    val symbol: String,
    val name: String,
    val market: String,
    val sector: String?,
    val price: BigDecimal,
    val changeRate: Double,
    val changeAmount: BigDecimal,
    val volume: Long,
    val amount: BigDecimal,
    val buyRatio: Int,
    val sellRatio: Int,
) {
    companion object {
        fun from(i: ScreenerItem) = ScreenerItemResponse(
            rank         = i.rank,
            stockId      = i.stockId,
            symbol       = i.symbol,
            name         = i.name,
            market       = i.market,
            sector       = i.sector,
            price        = i.price,
            changeRate   = i.changeRate,
            changeAmount = i.changeAmount,
            volume       = i.volume,
            amount       = i.amount,
            buyRatio     = i.buyRatio,
            sellRatio    = i.sellRatio,
        )
    }
}
