package com.monticker.api.matching.api

import com.monticker.api.matching.application.MatchingService
import com.monticker.api.matching.application.SubmitOrderRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

@RestController
@RequestMapping("/api/matching")
class MatchingController(private val matchingService: MatchingService) {

    // TODO: replace with JWT principal after auth is implemented
    private val tempUserId = 1L

    @PostMapping("/orders")
    fun submitOrder(@RequestBody req: SubmitOrderRequest): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(matchingService.submitOrder(tempUserId, req))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @DeleteMapping("/orders/{id}")
    fun cancelOrder(@PathVariable id: Long): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(matchingService.cancelOrder(tempUserId, id))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/orders")
    fun getActiveOrders(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getActiveOrders(tempUserId))

    @GetMapping("/orders/{id}/fills")
    fun getOrderFills(@PathVariable id: Long): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(matchingService.getOrderFills(tempUserId, id))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/fills")
    fun getMyFills(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getMyFills(tempUserId))
}
