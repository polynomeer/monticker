package com.monticker.api.ai

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.stock.application.StockService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/stocks/{stockId}/summary")
class StockSummaryController(
    private val stockSummaryService: StockSummaryService,
    private val stockService: StockService,
) {
    @GetMapping
    @RateLimited(limit = 30, windowSec = 3600, keyPrefix = "ai.summary")
    fun getSummary(@PathVariable stockId: Long): ResponseEntity<SummaryResponse> {
        return try {
            val stock = stockService.getById(stockId)
            val summary = stockSummaryService.getSummary(stockId, stock.name)
            ResponseEntity.ok(SummaryResponse(stockId = stockId, summary = summary))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
}

data class SummaryResponse(val stockId: Long, val summary: String)
