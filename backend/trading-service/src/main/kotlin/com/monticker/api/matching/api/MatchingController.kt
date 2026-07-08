package com.monticker.api.matching.api

import com.monticker.api.matching.application.MatchingService
import com.monticker.api.matching.application.SubmitOrderRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

@RestController
@RequestMapping("/api/matching")
class MatchingController(private val matchingService: MatchingService) {

    private fun userId(): Long {
        val attrs = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
            as org.springframework.web.context.request.ServletRequestAttributes
        return attrs.request.getHeader("X-User-Id")?.toLong()
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "X-User-Id header required")
    }

    @PostMapping("/orders")
    fun submitOrder(@RequestBody req: SubmitOrderRequest): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(matchingService.submitOrderChecked(
                userId         = userId(),
                stockId        = req.stockId,
                side           = req.side,
                quantity       = req.quantity,
                estimatedPrice = req.limitPrice ?: java.math.BigDecimal.ZERO,
                req            = req,
            ))
        } catch (e: com.monticker.api.common.aop.RiskLimitException) {
            ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .body(mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @DeleteMapping("/orders/{id}")
    fun cancelOrder(@PathVariable id: Long): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(matchingService.cancelOrder(userId(), id))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/orders")
    fun getActiveOrders(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getActiveOrders(userId()))

    @GetMapping("/orders/{id}/fills")
    fun getOrderFills(@PathVariable id: Long): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(matchingService.getOrderFills(userId(), id))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/fills")
    fun getMyFills(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getMyFills(userId()))
}
