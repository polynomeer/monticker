package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.ConditionalOrderInsights
import com.monticker.api.brokerage.application.ConditionalOrderLeg
import com.monticker.api.brokerage.application.ConditionalOrderQuote
import com.monticker.api.brokerage.application.ConditionalOrderStats
import com.monticker.api.brokerage.application.ConditionalTriggerPage
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.application.ConditionalOrderService
import com.monticker.api.brokerage.application.PriceFeed
import com.monticker.api.brokerage.domain.ConditionalOrder
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.common.aop.RateLimited
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

// ── 요청 DTO ───────────────────────────────────────────────────────────────────

data class ConditionalOrderLegRequest(
    @field:NotBlank val triggerType: String,
    @field:Positive val triggerPrice: BigDecimal,
    @field:NotBlank val orderType: String,
    val limitPrice: BigDecimal? = null,
) {
    fun toLeg() = ConditionalOrderLeg(
        triggerType  = ConditionalTriggerType.valueOf(triggerType.uppercase()),
        triggerPrice = triggerPrice,
        orderType    = OrderType.valueOf(orderType.uppercase()),
        limitPrice   = limitPrice,
    )
}

data class CreateConditionalOrderRequest(
    @field:NotBlank val symbol: String,
    @field:NotBlank val side: String,
    @field:Positive val quantity: Int,
    @field:Valid val leg: ConditionalOrderLegRequest,
    /** 유효 기간(일). 없으면 90일(이전 고정값). [ConditionalOrderService.MIN_VALID_DAYS]..[ConditionalOrderService.MAX_VALID_DAYS]. */
    @field:Min(1) @field:Max(90) val validDays: Int? = null,
)

data class CreateOcoOrderRequest(
    @field:NotBlank val symbol: String,
    @field:NotBlank val side: String,
    @field:Positive val quantity: Int,
    @field:Valid @field:Size(min = 2, max = 2) val legs: List<ConditionalOrderLegRequest>,
    @field:Min(1) @field:Max(90) val validDays: Int? = null,
)

// ── 응답 DTO ───────────────────────────────────────────────────────────────────

data class ConditionalOrderResponse(
    val id: Long,
    val stockId: Long,
    val symbol: String,
    val side: String,
    val triggerType: String,
    val triggerPrice: BigDecimal,
    val orderType: String,
    val limitPrice: BigDecimal?,
    val quantity: Int,
    val ocoGroupId: String?,
    val status: String,
    val failReason: String?,
    val executedOrderId: Long?,
    val createdAt: Instant,
    val triggeredAt: Instant?,
    val expiresAt: Instant?,
    /** ADR-060 — ACTIVE일 때만: LIVE | STALE | NONE. NONE·STALE이면 발동하지 않는다. */
    val priceFeed: String? = null,
)

// ── 컨트롤러 ───────────────────────────────────────────────────────────────────

/** ADR-032 — 조건부 주문(스탑로스/익절/OCO). 등록·조회·취소만 담당 — 발동은 ConditionalOrderEvaluator가 별도로 처리한다. */
@RestController
@RequestMapping("/api/brokerage/conditional-orders")
class ConditionalOrderController(
    private val conditionalOrderService: ConditionalOrderService,
    private val brokerageService: BrokerageService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val insights: ConditionalOrderInsights,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @PostMapping
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "conditional-order.create")
    fun create(
        @RequestHeader("Authorization") token: String,
        @Valid @RequestBody req: CreateConditionalOrderRequest,
    ): ResponseEntity<ConditionalOrderResponse> {
        val uid = userId(token)
        brokerageService.requireCurrentConsents(uid)   // ADR-068 — 등록만. 이미 걸린 주문의 발동은 막지 않는다
        val order = conditionalOrderService.create(
            uid, req.symbol, OrderSide.valueOf(req.side.uppercase()), req.quantity, req.leg.toLeg(), req.validDays,
        )
        return ResponseEntity.ok(order.toResponse())
    }

    @PostMapping("/oco")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "conditional-order.create")
    fun createOco(
        @RequestHeader("Authorization") token: String,
        @Valid @RequestBody req: CreateOcoOrderRequest,
    ): ResponseEntity<List<ConditionalOrderResponse>> {
        val uid = userId(token)
        brokerageService.requireCurrentConsents(uid)   // ADR-068
        val orders = conditionalOrderService.createOco(
            uid, req.symbol, OrderSide.valueOf(req.side.uppercase()), req.quantity, req.legs.map { it.toLeg() }, req.validDays,
        )
        return ResponseEntity.ok(orders.map { it.toResponse() })
    }

    @GetMapping
    fun getAll(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<ConditionalOrderResponse>> {
        val uid = userId(token)
        val orders = conditionalOrderService.getAll(uid, pageable)
        val feeds = conditionalOrderService.priceFeeds(uid, orders.content)
        return ResponseEntity.ok(orders.map { it.toResponse(feeds[it.id]) })
    }

    /** 상태별 건수와 이번 달(KST) 발동 건수 — 페이지에 보이는 행이 아니라 전체 기준. 읽기 전용. */
    @GetMapping("/stats")
    fun stats(@RequestHeader("Authorization") token: String): ResponseEntity<ConditionalOrderStats> =
        ResponseEntity.ok(insights.stats(userId(token)))

    /** 발동 기록(발동 시각 최신순) + 발동이 낸 증권사 주문의 현재 상태. 읽기 전용. */
    @GetMapping("/triggers")
    fun triggers(
        @RequestHeader("Authorization") token: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<ConditionalTriggerPage> = ResponseEntity.ok(insights.triggers(userId(token), page, size))

    /** 감시 중인 종목의 최근가를 한 번에. 종목은 내 ACTIVE 조건부 주문에서만 정해진다. 읽기 전용. */
    @GetMapping("/quotes")
    fun quotes(@RequestHeader("Authorization") token: String): ResponseEntity<List<ConditionalOrderQuote>> =
        ResponseEntity.ok(insights.quotes(userId(token)))

    @DeleteMapping("/{id}")
    fun cancel(
        @RequestHeader("Authorization") token: String,
        @PathVariable id: Long,
    ): ResponseEntity<ConditionalOrderResponse> {
        val order = conditionalOrderService.cancel(userId(token), id)
        return ResponseEntity.ok(order.toResponse())
    }

    private fun ConditionalOrder.toResponse(priceFeed: PriceFeed? = null) = ConditionalOrderResponse(
        id              = id,
        stockId         = stockId,
        symbol          = symbol,
        side            = side.name,
        triggerType     = triggerType.name,
        triggerPrice    = triggerPrice,
        orderType       = orderType.name,
        limitPrice      = limitPrice,
        quantity        = quantity,
        ocoGroupId      = ocoGroupId?.toString(),
        status          = status.name,
        failReason      = failReason,
        executedOrderId = executedOrderId,
        createdAt       = createdAt,
        triggeredAt     = triggeredAt,
        expiresAt       = expiresAt,
        priceFeed       = priceFeed?.name,
    )
}
