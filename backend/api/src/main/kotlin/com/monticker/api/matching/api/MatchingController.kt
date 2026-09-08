package com.monticker.api.matching.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.matching.application.MatchingService
import com.monticker.api.matching.application.SubmitOrderRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

@Validated
@RestController
@RequestMapping("/api/matching")
class MatchingController(private val matchingService: MatchingService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @PostMapping("/orders")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "matching.order")
    fun submitOrder(@RequestBody req: SubmitOrderRequest): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.submitOrderChecked(
            userId         = userId(),
            stockId        = req.stockId,
            side           = req.side,
            quantity       = req.quantity,
            estimatedPrice = req.limitPrice ?: java.math.BigDecimal.ZERO,
            req            = req,
        ))

    @DeleteMapping("/orders/{id}")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "matching.cancel")
    fun cancelOrder(@PathVariable id: Long): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.cancelOrder(userId(), id))

    @GetMapping("/orders")
    fun getActiveOrders(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getActiveOrders(userId()))

    @GetMapping("/orders/{id}/fills")
    fun getOrderFills(@PathVariable id: Long): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getOrderFills(userId(), id))

    @GetMapping("/fills")
    fun getMyFills(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getMyFills(userId()))
}
