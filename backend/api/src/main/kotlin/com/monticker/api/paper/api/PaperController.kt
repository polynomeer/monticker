package com.monticker.api.paper.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.paper.application.PaperOrderRequest
import com.monticker.api.paper.application.PaperOrderResponse
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.matching.submit.OrderOriginType
import com.monticker.api.paper.application.PaperTradingService
import com.monticker.api.paper.application.TradeResultResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@Validated
@RestController
@RequestMapping("/api/paper")
class PaperController(
    private val tradingService: PaperTradingService,
    private val portfolioQueryService: PaperPortfolioQueryService,
    private val realizedPnlService: PaperRealizedPnlService,
) {
    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/portfolio")
    fun getPortfolio() = ResponseEntity.ok(portfolioQueryService.getPortfolio(userId()))

    @PostMapping("/buy")
    @RateLimited(limit = 60, windowSec = 60, keyPrefix = "paper.buy")
    fun buy(@RequestBody req: TradeRequest): ResponseEntity<TradeResultResponse> =
        ResponseEntity.ok(tradingService.buy(userId(), req.stockId, req.quantity))

    @PostMapping("/sell")
    @RateLimited(limit = 60, windowSec = 60, keyPrefix = "paper.sell")
    fun sell(@RequestBody req: TradeRequest): ResponseEntity<TradeResultResponse> =
        ResponseEntity.ok(tradingService.sell(userId(), req.stockId, req.quantity))

    /** ADR-074 — 시장가·지정가 공용 주문. 미체결 지정가의 조회·취소는 `/api/matching/orders`(같은 계좌)다. */
    @PostMapping("/orders")
    @RateLimited(limit = 60, windowSec = 60, keyPrefix = "paper.order")
    fun placeOrder(@RequestBody req: PaperOrderRequest): ResponseEntity<PaperOrderResponse> =
        ResponseEntity.ok(tradingService.placeOrder(userId(), req))

    @GetMapping("/history")
    fun getHistory(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = ResponseEntity.ok(portfolioQueryService.getHistory(userId(), page, size.coerceIn(1, 100)))

    @PostMapping("/reset")
    @RateLimited(limit = 3, windowSec = 86400, keyPrefix = "paper.reset")
    fun reset(): ResponseEntity<Void> {
        tradingService.reset(userId())
        return ResponseEntity.noContent().build()
    }

    /**
     * ADR-085 — 출처별 실현 손익(예: `?origin=WATCH_RULE` → 규칙 경유 손익, 규칙별 내역 포함).
     * 매도 체결의 출처로 귀속하고 평균단가는 이동평균법이다([PaperRealizedPnlService]).
     */
    @GetMapping("/pnl/by-origin")
    fun getPnlByOrigin(@RequestParam origin: String) = ResponseEntity.ok(
        realizedPnlService.byOrigin(
            userId(),
            runCatching { OrderOriginType.valueOf(origin.uppercase()) }
                .getOrElse { throw IllegalArgumentException("origin은 ${OrderOriginType.entries.joinToString()} 중 하나여야 합니다") },
        )
    )

    @GetMapping("/risk")
    fun getRiskMetrics() = ResponseEntity.ok(portfolioQueryService.getRiskMetrics(userId()))
}

data class TradeRequest(val stockId: Long, val quantity: Int)
