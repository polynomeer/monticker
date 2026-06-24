package com.monticker.api.paper.api

import com.monticker.api.paper.application.PaperTradingService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/paper")
class PaperController(private val service: PaperTradingService) {

    @GetMapping("/portfolio")
    fun portfolio(@AuthenticationPrincipal userId: Long) =
        ResponseEntity.ok(service.getPortfolio(userId))

    @PostMapping("/buy")
    fun buy(@AuthenticationPrincipal userId: Long, @RequestBody req: TradeRequest): ResponseEntity<*> =
        try { ResponseEntity.ok(service.buy(userId, req.stockId, req.quantity)) }
        catch (e: IllegalArgumentException) { ResponseEntity.badRequest().body(mapOf("error" to e.message)) }
        catch (e: IllegalStateException) { ResponseEntity.badRequest().body(mapOf("error" to e.message)) }

    @PostMapping("/sell")
    fun sell(@AuthenticationPrincipal userId: Long, @RequestBody req: TradeRequest): ResponseEntity<*> =
        try { ResponseEntity.ok(service.sell(userId, req.stockId, req.quantity)) }
        catch (e: IllegalArgumentException) { ResponseEntity.badRequest().body(mapOf("error" to e.message)) }
        catch (e: IllegalStateException) { ResponseEntity.badRequest().body(mapOf("error" to e.message)) }

    @GetMapping("/history")
    fun history(@AuthenticationPrincipal userId: Long) =
        ResponseEntity.ok(service.getHistory(userId))

    @PostMapping("/reset")
    fun reset(@AuthenticationPrincipal userId: Long): ResponseEntity<*> {
        service.reset(userId)
        return ResponseEntity.ok(mapOf("message" to "계좌가 초기화되었습니다"))
    }
}

data class TradeRequest(val stockId: Long, val quantity: Int)
