package com.monticker.api.stock.api

import com.monticker.api.stock.application.StockService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/stocks")
class StockController(
    private val stockService: StockService,
) {
    @GetMapping("/search")
    fun search(@RequestParam query: String): ResponseEntity<List<StockResponse>> {
        if (query.isBlank() || query.length < 1) {
            return ResponseEntity.badRequest().build()
        }
        val results = stockService.search(query).map { StockResponse.from(it) }
        return ResponseEntity.ok(results)
    }

    @GetMapping("/{stockId}")
    fun getById(@PathVariable stockId: Long): ResponseEntity<StockResponse> {
        return try {
            ResponseEntity.ok(StockResponse.from(stockService.getById(stockId)))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
}
