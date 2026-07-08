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
    private fun userId(): Long {
        val attrs = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
            as org.springframework.web.context.request.ServletRequestAttributes
        return attrs.request.getHeader("X-User-Id")?.toLong()
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "X-User-Id header required")
    }

    @GetMapping("/portfolio")
    fun getPortfolio() = ResponseEntity.ok(portfolioQueryService.getPortfolio(userId()))

    @PostMapping("/buy")
    fun buy(@RequestBody req: TradeRequest): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(tradingService.buy(userId(), req.stockId, req.quantity))
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
            ResponseEntity.ok(tradingService.sell(userId(), req.stockId, req.quantity))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
    }

    @GetMapping("/history")
    fun getHistory() = ResponseEntity.ok(portfolioQueryService.getHistory(userId()))

    @PostMapping("/reset")
    fun reset(): ResponseEntity<Void> {
        tradingService.reset(userId())
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/risk")
    fun getRiskMetrics() = ResponseEntity.ok(portfolioQueryService.getRiskMetrics(userId()))
}

data class TradeRequest(val stockId: Long, val quantity: Int)
