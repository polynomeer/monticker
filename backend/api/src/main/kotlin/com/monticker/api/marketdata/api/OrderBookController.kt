package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.OrderBookService
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@Validated
@RestController
@RequestMapping("/api/stocks/{stockId}/orderbook")
class OrderBookController(private val orderBookService: OrderBookService) {
    @GetMapping
    fun getOrderBook(@PathVariable stockId: Long): ResponseEntity<*> =
        try { ResponseEntity.ok(orderBookService.getOrderBook(stockId)) }
        catch (e: IllegalArgumentException) { ResponseEntity.notFound().build<Any>() }
}
