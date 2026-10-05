package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.CandleService
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@Validated
@RestController
@RequestMapping("/api/stocks/{stockId}/candles")
class CandleController(private val candleService: CandleService) {

    /** 과거 하루(KST)의 1분봉 전부 — 주문 리플레이 재생용. */
    @GetMapping("/intraday")
    fun getIntraday(
        @PathVariable stockId: Long,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) date: java.time.LocalDate,
    ): ResponseEntity<List<CandleResponse>> =
        try {
            ResponseEntity.ok(candleService.getIntradayCandles(stockId, date).map { CandleResponse.from(it) })
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }

    @GetMapping
    fun getCandles(
        @PathVariable stockId: Long,
        @RequestParam(defaultValue = "1d") interval: String,
        @RequestParam(required = false) from: Instant? = null,
        @RequestParam(required = false) to: Instant? = null,
    ): ResponseEntity<List<CandleResponse>> {
        return try {
            val candles = candleService.getCandles(
                stockId = stockId,
                interval = interval,
                from = from ?: Instant.now().minusSeconds(86400L * 30),
                to = to ?: Instant.now(),
            ).map { CandleResponse.from(it) }
            ResponseEntity.ok(candles)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }
}

data class CandleResponse(
    val time: Long,   // Unix timestamp (seconds) — Lightweight Charts format
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
) {
    companion object {
        fun from(c: com.monticker.api.marketdata.domain.Candle) = CandleResponse(
            time   = c.time.epochSecond,
            open   = c.open,
            high   = c.high,
            low    = c.low,
            close  = c.close,
            volume = c.volume,
        )
    }
}
