package com.monticker.api.matching.application

import com.monticker.api.common.aop.RiskChecked
import com.monticker.api.common.aop.Timed
import com.monticker.api.matching.domain.*
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.statemachine.OrderEvents
import com.monticker.api.matching.statemachine.OrderStateMachineService
import com.monticker.api.matching.statemachine.OrderStates
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

data class SubmitOrderRequest(
    val stockId: Long,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
)

data class FillDto(
    val id: Long,
    val orderId: Long,
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val fillPrice: BigDecimal,
    val amount: BigDecimal,
    val fee: BigDecimal,
    val filledAt: Instant,
)

data class OrderDto(
    val id: Long,
    val stockId: Long,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    val status: String,
    val rejectReason: String?,
    val createdAt: Instant,
)

data class SubmitOrderResponse(
    val order: OrderDto,
    val fills: List<FillDto>,
    val message: String,
)

@Service
@Transactional
class MatchingService(
    private val orderRepo: OrderRepository,
    private val fillRepo: FillRepository,
    private val orderBookService: MatchingOrderBookService,
    private val riskChecker: RiskCheckerService,
    private val jdbc: JdbcTemplate,
    private val stateMachineService: OrderStateMachineService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private fun getCurrentPrice(stockId: Long): BigDecimal =
        jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId
        ) ?: throw IllegalStateException("현재가 조회 불가: stockId=$stockId")

    private fun getAccountCash(userId: Long): BigDecimal {
        return jdbc.queryForObject(
            "SELECT cash FROM paper_accounts WHERE user_id = ?",
            BigDecimal::class.java, userId
        ) ?: run {
            // Create account if not exists
            jdbc.update(
                "INSERT INTO paper_accounts (user_id, cash, created_at, updated_at) VALUES (?, 10000000, now(), now()) ON CONFLICT (user_id) DO NOTHING",
                userId
            )
            BigDecimal("10000000")
        }
    }

    private fun adjustCash(userId: Long, delta: BigDecimal) {
        jdbc.update(
            "UPDATE paper_accounts SET cash = cash + ?, updated_at = now() WHERE user_id = ?",
            delta, userId
        )
    }

    /**
     * AOP 리스크 체크 진입점 — 파라미터명으로 stockId/side/quantity/estimatedPrice를 추출.
     * Aspect 통과 후 submitOrder로 위임한다.
     */
    @RiskChecked
    fun submitOrderChecked(
        userId: Long,
        stockId: Long,
        side: String,
        quantity: Int,
        estimatedPrice: BigDecimal,
        req: SubmitOrderRequest,
    ): SubmitOrderResponse = submitOrder(userId, req)

    @Timed("matching.submit_order", tags = ["module=matching"])
    fun submitOrder(userId: Long, req: SubmitOrderRequest): SubmitOrderResponse {
        require(req.quantity > 0) { "수량은 1 이상이어야 합니다" }
        require(req.side in listOf("BUY", "SELL")) { "side는 BUY 또는 SELL이어야 합니다" }
        require(req.orderType in listOf("MARKET", "LIMIT")) { "orderType은 MARKET 또는 LIMIT이어야 합니다" }
        if (req.orderType == "LIMIT") {
            require(req.limitPrice != null && req.limitPrice > BigDecimal.ZERO) {
                "LIMIT 주문에는 limit_price가 필요합니다"
            }
        }

        val stockExists = jdbc.queryForObject("SELECT COUNT(*) FROM stocks WHERE id = ?", Long::class.java, req.stockId) ?: 0L
        require(stockExists > 0) { "존재하지 않는 종목: stockId=${req.stockId}" }

        val currentPrice = getCurrentPrice(req.stockId)
        val estimatedPrice = if (req.orderType == "LIMIT") req.limitPrice!! else currentPrice

        // For BUY, reserve cash upfront
        if (req.side == "BUY") {
            val cash = getAccountCash(userId)
            val reserveAmount = estimatedPrice.multiply(BigDecimal(req.quantity))
            require(cash >= reserveAmount) { "잔고 부족: 필요 $reserveAmount, 보유 $cash" }
            adjustCash(userId, reserveAmount.negate())
        }

        val order = orderRepo.save(Order(
            userId = userId,
            stockId = req.stockId,
            side = OrderSide.valueOf(req.side),
            orderType = OrderType.valueOf(req.orderType),
            quantity = req.quantity,
            limitPrice = req.limitPrice,
            status = OrderStatus.PENDING,
        ))

        // Determine fill price for mock environment
        val fillPrice: BigDecimal? = when {
            req.orderType == "MARKET" -> currentPrice
            req.side == "BUY" && req.limitPrice!! >= currentPrice -> currentPrice
            req.side == "SELL" && req.limitPrice!! <= currentPrice -> currentPrice
            else -> null
        }

        val fills = mutableListOf<FillDto>()

        if (fillPrice != null) {
            val fillQty = req.quantity
            val fillAmount = fillPrice.multiply(BigDecimal(fillQty))

            val fill = fillRepo.save(Fill(
                orderId = order.id,
                userId = userId,
                stockId = req.stockId,
                side = req.side,
                quantity = fillQty,
                fillPrice = fillPrice,
                amount = fillAmount,
                fee = BigDecimal.ZERO,
            ))
            fills.add(fill.toDto())

            // StateMachine으로 상태 전이 — PENDING → FILLED
            stateMachineService.transition(
                orderId      = order.id,
                currentState = OrderStates.PENDING,
                event        = OrderEvents.COMPLETE_FILL,
            )
            order.fill(fillQty, fillPrice)
            orderRepo.save(order)

            if (req.side == "BUY") {
                val reserved = estimatedPrice.multiply(BigDecimal(req.quantity))
                val refund = reserved - fillAmount
                if (refund > BigDecimal.ZERO) adjustCash(userId, refund)
            } else {
                adjustCash(userId, fillAmount)
            }

            // wallet/quant 모듈에 체결 이벤트 발행 — AFTER_COMMIT 리스너가 원장 기록
            eventPublisher.publishEvent(
                OrderFilledEvent(
                    orderId   = order.id,
                    userId    = userId,
                    stockId   = req.stockId,
                    fillId    = fill.id,
                    side      = req.side,
                    quantity  = fillQty,
                    fillPrice = fillPrice,
                    amount    = fillAmount,
                )
            )
        } else {
            // LIMIT order that doesn't fill immediately — add to order book
            orderBookService.submit(order)
        }

        return SubmitOrderResponse(
            order = order.toDto(),
            fills = fills,
            message = if (fills.isNotEmpty()) "주문 체결 완료" else "주문 접수 완료 (미체결)",
        )
    }

    fun cancelOrder(userId: Long, orderId: Long): OrderDto {
        val order = orderRepo.findById(orderId).orElseThrow { NoSuchElementException("주문 없음: $orderId") }
        require(order.userId == userId) { "본인의 주문만 취소할 수 있습니다" }

        orderBookService.cancel(order.stockId, orderId, order.side)

        var refundAmount = BigDecimal.ZERO
        if (order.side == OrderSide.BUY) {
            val refundPrice = order.limitPrice ?: BigDecimal.ZERO
            refundAmount = refundPrice.multiply(BigDecimal(order.remainingQty))
            if (refundAmount > BigDecimal.ZERO) adjustCash(userId, refundAmount)
        }

        // StateMachine으로 상태 전이
        stateMachineService.transition(
            orderId      = order.id,
            currentState = OrderStates.valueOf(order.status.name),
            event        = OrderEvents.CANCEL,
        )
        order.cancel()
        val saved = orderRepo.save(order)

        // wallet 모듈에 취소 이벤트 발행 (환불 원장 기록)
        eventPublisher.publishEvent(
            OrderCancelledEvent(
                orderId      = order.id,
                userId       = userId,
                stockId      = order.stockId,
                side         = order.side.name,
                refundAmount = refundAmount,
            )
        )
        return saved.toDto()
    }

    @Transactional(readOnly = true)
    fun getActiveOrders(userId: Long): List<OrderDto> =
        orderRepo.findByUserIdAndStatusIn(
            userId, listOf(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)
        ).map { it.toDto() }

    @Transactional(readOnly = true)
    fun getOrderFills(userId: Long, orderId: Long): List<FillDto> {
        val order = orderRepo.findById(orderId).orElseThrow { NoSuchElementException("주문 없음: $orderId") }
        require(order.userId == userId) { "접근 권한 없음" }
        return fillRepo.findAllByOrderId(orderId).map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun getMyFills(userId: Long): List<FillDto> =
        fillRepo.findAllByUserIdOrderByFilledAtDesc(userId).map { it.toDto() }

    private fun Order.toDto() = OrderDto(
        id = id,
        stockId = stockId,
        side = side.name,
        orderType = orderType.name,
        quantity = quantity,
        limitPrice = limitPrice,
        filledQty = filledQty,
        avgFillPrice = avgFillPrice,
        status = status.name,
        rejectReason = rejectReason,
        createdAt = createdAt,
    )

    private fun Fill.toDto() = FillDto(
        id = id,
        orderId = orderId,
        stockId = stockId,
        side = side,
        quantity = quantity,
        fillPrice = fillPrice,
        amount = amount,
        fee = fee,
        filledAt = filledAt,
    )
}
