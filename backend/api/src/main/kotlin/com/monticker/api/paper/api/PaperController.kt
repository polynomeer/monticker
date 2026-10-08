package com.monticker.api.paper.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.paper.application.PaperAccountResponse
import com.monticker.api.paper.application.PaperAccountService
import com.monticker.api.paper.application.PaperOrderRequest
import com.monticker.api.paper.application.PaperOrderResponse
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.matching.submit.OrderOriginType
import com.monticker.api.paper.application.PaperTradingService
import com.monticker.api.paper.application.TradeHistoryFilter
import com.monticker.api.paper.application.TradeHistoryResponse
import com.monticker.api.paper.application.TradeResultResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Validated
@RestController
@RequestMapping("/api/paper")
class PaperController(
    private val tradingService: PaperTradingService,
    private val portfolioQueryService: PaperPortfolioQueryService,
    private val realizedPnlService: PaperRealizedPnlService,
    private val accountService: PaperAccountService,
) {
    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/portfolio")
    fun getPortfolio() = ResponseEntity.ok(portfolioQueryService.getPortfolio(userId()))

    /** ADR-089 — 내 모의 계좌의 시작 자금·현금. 아직 없으면 204(첫 주문·온보딩에서 만들어진다). */
    @GetMapping("/account")
    fun getAccount(): ResponseEntity<PaperAccountResponse> =
        accountService.find(userId())?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    /**
     * ADR-089 — 시작 자금을 정해 모의 계좌를 처음 만든다. 허용값 밖은 400, 이미 다른 시작 자금의 계좌가 있으면 409,
     * 같은 값으로 다시 부르면 200(created=false). 기존 잔고는 이 경로로 바뀌지 않는다.
     */
    @PostMapping("/account")
    @RateLimited(limit = 10, windowSec = 60, keyPrefix = "paper.account.open")
    fun openAccount(@RequestBody req: OpenPaperAccountRequest): ResponseEntity<PaperAccountResponse> {
        val res = accountService.open(userId(), req.initialCapital)
        return if (res.created) ResponseEntity.status(201).body(res) else ResponseEntity.ok(res)
    }

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

    /**
     * 내 모의 체결 내역(최신순). `stockId`로 한 종목만, `from`(포함)·`to`(제외, ISO-8601 Instant)로 체결 시각 구간만
     * 좁힐 수 있다 — 차트 마커처럼 종목 하나를 보는 화면이 전체 내역을 받아 거르지 않도록. 항상 인증 사용자 것만 돈다.
     */
    @GetMapping("/history")
    fun getHistory(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) stockId: Long?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): ResponseEntity<List<TradeHistoryResponse>> {
        require(page >= 0) { "page는 0 이상이어야 합니다" }
        val filter = TradeHistoryFilter(stockId, from, to)
        return ResponseEntity.ok(portfolioQueryService.getHistory(userId(), page, size.coerceIn(1, 100), filter))
    }

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

data class OpenPaperAccountRequest(val initialCapital: java.math.BigDecimal? = null)
