package com.monticker.api.matching.application

import com.monticker.api.common.aop.RiskChecked
import com.monticker.api.common.aop.Timed
import com.monticker.api.common.domain.Money
import com.monticker.api.common.domain.Price
import com.monticker.api.matching.domain.*
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.saga.OrderSagaOrchestrator
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
    private val fillQueryService: FillQueryService,
    private val orderBookService: MatchingOrderBookService,
    private val riskChecker: RiskCheckerService,
    private val jdbc: JdbcTemplate,
    private val stateMachineService: OrderStateMachineService,
    private val eventPublisher: ApplicationEventPublisher,
    private val sagaOrchestrator: OrderSagaOrchestrator,
) {
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
    fun submitOrder(userId: Long, req: SubmitOrderRequest): SubmitOrderResponse =
        sagaOrchestrator.execute(userId, req)

    fun cancelOrder(userId: Long, orderId: Long): OrderDto {
        val order = orderRepo.findById(orderId).orElseThrow { NoSuchElementException("주문 없음: $orderId") }
        require(order.userId == userId) { "본인의 주문만 취소할 수 있습니다" }

        orderBookService.cancel(order.stockId, orderId, order.side)

        val refundAmount = if (order.side == OrderSide.BUY) {
            order.limitPrice?.toMoney(order.remainingQty) ?: Money.ZERO
        } else Money.ZERO

        if (refundAmount > Money.ZERO) {
            jdbc.update("UPDATE paper_accounts SET cash = cash + ?, updated_at = now() WHERE user_id = ?",
                refundAmount.amount, userId)
        }

        stateMachineService.transition(
            orderId      = order.id,
            currentState = OrderStates.valueOf(order.status.name),
            event        = OrderEvents.CANCEL,
        )
        order.cancel()
        val saved = orderRepo.save(order)

        eventPublisher.publishEvent(
            OrderCancelledEvent(
                orderId      = order.id,
                userId       = userId,
                stockId      = order.stockId,
                side         = order.side.name,
                refundAmount = refundAmount.amount,
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
    fun getOrderFills(userId: Long, orderId: Long): List<FillDto> =
        fillQueryService.findByOrderId(orderId, userId)

    @Transactional(readOnly = true)
    fun getMyFills(userId: Long): List<FillDto> =
        fillQueryService.findByUserId(userId)

    private fun Order.toDto() = OrderDto(
        id = id,
        stockId = stockId,
        side = side.name,
        orderType = orderType.name,
        quantity = quantity,
        limitPrice = limitPrice?.amount,
        filledQty = filledQty,
        avgFillPrice = avgFillPrice?.amount,
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
        fillPrice = fillPrice.amount,
        amount = amount.amount,
        fee = fee.amount,
        filledAt = filledAt,
    )
}
