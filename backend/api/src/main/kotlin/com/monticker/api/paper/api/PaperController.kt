package com.monticker.api.paper.api

import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.paper.application.PaperTradingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/paper")
class PaperController(
    private val tradingService: PaperTradingService,
    private val portfolioQueryService: PaperPortfolioQueryService,
) {
    // TODO: replace hardcoded userId with JWT principal after auth is implemented
    private val tempUserId = 1L

    @GetMapping("/portfolio")
    fun getPortfolio() = ResponseEntity.ok(portfolioQueryService.getPortfolio(tempUserId))

    @PostMapping("/buy")
    fun buy(@RequestBody req: TradeRequest): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(tradingService.buy(tempUserId, req.stockId, req.quantity))
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
            ResponseEntity.ok(tradingService.sell(tempUserId, req.stockId, req.quantity))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/history")
    fun getHistory() = ResponseEntity.ok(portfolioQueryService.getHistory(tempUserId))

    @PostMapping("/reset")
    fun reset(): ResponseEntity<Void> {
        tradingService.reset(tempUserId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/risk")
    fun getRiskMetrics() = ResponseEntity.ok(portfolioQueryService.getRiskMetrics(tempUserId))
}

data class TradeRequest(val stockId: Long, val quantity: Int)
