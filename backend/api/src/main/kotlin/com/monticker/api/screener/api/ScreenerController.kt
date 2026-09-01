package com.monticker.api.screener.api

import com.monticker.api.screener.application.ScreenerResult
import com.monticker.api.screener.application.ScreenerService
import com.monticker.api.screener.domain.ScreenerItem
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@Validated
@RestController
@RequestMapping("/api/screener")
class ScreenerController(private val screenerService: ScreenerService) {

    /**
     * 시세 기반 스크리너 (기존).
     *
     * GET /api/screener?tab=realtime&market=domestic&sort=amount
     */
    @GetMapping
    fun getScreener(
        @RequestParam(defaultValue = "realtime") tab: String,
        @RequestParam(defaultValue = "all")      market: String,
        @RequestParam(defaultValue = "amount")   sort: String,
        @RequestParam(defaultValue = "20")       limit: Int,
        @RequestParam(defaultValue = "0")        offset: Int,
        @RequestParam(defaultValue = "all")      marketCapTier: String,
    ): ResponseEntity<ScreenerResponse> {
        val result = screenerService.getItems(tab, market, sort, limit, offset, marketCapTier)
        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * ES 키워드 검색 스크리너.
     * 종목 이름·심볼·섹터 키워드로 ES에서 종목을 검색한 뒤 시세 데이터를 붙여 반환한다.
     *
     * GET /api/screener/search?query=반도체
     * GET /api/screener/search?query=삼성&sort=volume
     */
    @GetMapping("/search")
    fun searchScreener(
        @RequestParam query: String,
        @RequestParam(defaultValue = "amount") sort: String,
        @RequestParam(defaultValue = "20")     limit: Int,
    ): ResponseEntity<ScreenerResponse> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        val result = screenerService.search(query, sort, limit.coerceIn(1, 50))
        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * 명시적 종목 ID 목록의 시세를 조회한다 (관심종목 티커 스트립 등).
     *
     * GET /api/screener/quotes?ids=1,2,3
     */
    @GetMapping("/quotes")
    fun getQuotes(@RequestParam ids: String): ResponseEntity<ScreenerResponse> {
        val stockIds = ids.split(",").mapNotNull { it.trim().toLongOrNull() }.take(50)
        val result = screenerService.getByStockIds(stockIds)
        return ResponseEntity.ok(result.toResponse())
    }

    private fun ScreenerResult.toResponse() = ScreenerResponse(
        items     = items.map { ScreenerItemResponse.from(it) },
        total     = total,
        hasMore   = hasMore,
        updatedAt = Instant.now(),
    )
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
    val marketCap: Long?,
    val per: BigDecimal?,
    val pbr: BigDecimal?,
    val isFundamentalsMocked: Boolean,
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
            marketCap    = i.marketCap,
            per          = i.per,
            pbr          = i.pbr,
            isFundamentalsMocked = i.isFundamentalsMocked,
        )
    }
}
