package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.ConditionalOrderLeg
import com.monticker.api.brokerage.application.ConditionalOrderService
import com.monticker.api.brokerage.domain.ConditionalOrder
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.common.aop.RateLimited
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

// ── 요청 DTO ───────────────────────────────────────────────────────────────────

data class ConditionalOrderLegRequest(
    val triggerType: String,
    val triggerPrice: BigDecimal,
    val orderType: String,
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
    val symbol: String,
    val side: String,
    val quantity: Int,
    val leg: ConditionalOrderLegRequest,
)

data class CreateOcoOrderRequest(
    val symbol: String,
    val side: String,
    val quantity: Int,
    val legs: List<ConditionalOrderLegRequest>,
)

// ── 응답 DTO ───────────────────────────────────────────────────────────────────

data class ConditionalOrderResponse(
    val id: Long,
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
)

// ── 컨트롤러 ───────────────────────────────────────────────────────────────────

/** ADR-032 — 조건부 주문(스탑로스/익절/OCO). 등록·조회·취소만 담당 — 발동은 ConditionalOrderEvaluator가 별도로 처리한다. */
@RestController
@RequestMapping("/api/brokerage/conditional-orders")
class ConditionalOrderController(
    private val conditionalOrderService: ConditionalOrderService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @PostMapping
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "conditional-order.create")
    fun create(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: CreateConditionalOrderRequest,
    ): ResponseEntity<ConditionalOrderResponse> {
        val order = conditionalOrderService.create(
            userId(token), req.symbol, OrderSide.valueOf(req.side.uppercase()), req.quantity, req.leg.toLeg(),
        )
        return ResponseEntity.ok(order.toResponse())
    }

    @PostMapping("/oco")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "conditional-order.create")
    fun createOco(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: CreateOcoOrderRequest,
    ): ResponseEntity<List<ConditionalOrderResponse>> {
        val orders = conditionalOrderService.createOco(
            userId(token), req.symbol, OrderSide.valueOf(req.side.uppercase()), req.quantity, req.legs.map { it.toLeg() },
        )
        return ResponseEntity.ok(orders.map { it.toResponse() })
    }

    @GetMapping
    fun getAll(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<ConditionalOrderResponse>> {
        val page = conditionalOrderService.getAll(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    @DeleteMapping("/{id}")
    fun cancel(
        @RequestHeader("Authorization") token: String,
        @PathVariable id: Long,
    ): ResponseEntity<ConditionalOrderResponse> {
        val order = conditionalOrderService.cancel(userId(token), id)
        return ResponseEntity.ok(order.toResponse())
    }

    private fun ConditionalOrder.toResponse() = ConditionalOrderResponse(
        id              = id,
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
    )
}
