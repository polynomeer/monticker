package com.monticker.api.paper.api

import com.monticker.api.paper.application.PaperTradingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/paper")
class PaperController(private val service: PaperTradingService) {

    // TODO: replace hardcoded userId with JWT principal after auth is implemented
    private val tempUserId = 1L

    @GetMapping("/portfolio")
    fun getPortfolio() = ResponseEntity.ok(service.getPortfolio(tempUserId))

    @PostMapping("/buy")
    fun buy(@RequestBody req: TradeRequest): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(service.buy(tempUserId, req.stockId, req.quantity))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @PostMapping("/sell")
    fun sell(@RequestBody req: TradeRequest): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(service.sell(tempUserId, req.stockId, req.quantity))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/history")
    fun getHistory() = ResponseEntity.ok(service.getHistory(tempUserId))

    @PostMapping("/reset")
    fun reset(): ResponseEntity<Void> {
        service.reset(tempUserId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/risk")
    fun getRiskMetrics() = ResponseEntity.ok(service.getRiskMetrics(tempUserId))
}

data class TradeRequest(val stockId: Long, val quantity: Int)
