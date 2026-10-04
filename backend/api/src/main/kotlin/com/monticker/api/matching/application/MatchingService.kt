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
import com.monticker.api.matching.submit.MarketOrderResult
import com.monticker.api.matching.submit.OrderSubmitter
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
    /** ADR-051 — 서버 내부 발행 주문의 멱등 키. 컨트롤러 경로는 항상 null이다(사용자 입력으로 받지 않는다). */
    val idempotencyKey: String? = null,
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
    private val jdbc: JdbcTemplate,
    private val stateMachineService: OrderStateMachineService,
    private val eventPublisher: ApplicationEventPublisher,
    private val sagaOrchestrator: OrderSagaOrchestrator,
) : OrderSubmitter {
    /**
     * ADR-047 — paper 파사드(`/api/paper/buy|sell`)의 진입점. @RiskChecked 는 파라미터 이름(userId·stockId·side·
     * quantity)으로 판정 입력을 뽑으므로 여기 직접 건다. MARKET 주문은 즉시 체결 아니면 예외다 — 이 경로에 미체결은 없다.
     */
    @RiskChecked
    override fun submitMarket(
        userId: Long,
        stockId: Long,
        side: String,
        quantity: Int,
        idempotencyKey: String?,
    ): MarketOrderResult {
        // ADR-051 — 멱등 재제출. 아웃박스 재전달·컨슈머 리밸런싱으로 같은 이벤트가 두 번 와도 체결은 한 번이다.
        // 리스크 게이트(@RiskChecked)보다 뒤에 있는 것은 의도적이다 — 게이트는 부작용이 없고,
        // 여기서 먼저 빠져나가면 "이미 체결된 주문"이 한도 변화로 거부되는 모순이 생긴다.
        idempotencyKey?.let { key ->
            orderRepo.findByIdempotencyKey(key)?.let { return replayOf(it, key) }
        }
        val res = submitOrder(userId, SubmitOrderRequest(
            stockId = stockId, side = side, orderType = "MARKET", quantity = quantity, idempotencyKey = idempotencyKey,
        ))
        val fill = res.fills.singleOrNull() ?: throw IllegalStateException("시장가 주문이 체결되지 않았습니다: orderId=${res.order.id}")
        return MarketOrderResult(
            orderId = res.order.id, fillId = fill.id, stockId = fill.stockId, side = fill.side,
            quantity = fill.quantity, fillPrice = fill.fillPrice, amount = fill.amount, filledAt = fill.filledAt,
        )
    }

    /**
     * 이미 처리된 멱등 키의 결과를 체결 기록에서 그대로 복원한다. 새 주문도, 새 체결도 만들지 않는다.
     * 체결이 없으면(주문은 만들어졌지만 체결 전에 죽은 경우) 예외 — 호출자가 "미확정"으로 다루게 한다.
     */
    private fun replayOf(order: Order, key: String): MarketOrderResult {
        val fill = fillQueryService.findByOrderId(order.id, order.userId).singleOrNull()
            ?: throw IllegalStateException("멱등 재제출: 주문은 있으나 체결이 없습니다 key=$key orderId=${order.id}")
        return MarketOrderResult(
            orderId = order.id, fillId = fill.id, stockId = fill.stockId, side = fill.side,
            quantity = fill.quantity, fillPrice = fill.fillPrice, amount = fill.amount, filledAt = fill.filledAt,
        )
    }

    @RiskChecked
    fun submitOrderChecked(
        userId: Long,
        stockId: Long,
        side: String,
        quantity: Int,
        estimatedPrice: BigDecimal,
        req: SubmitOrderRequest,
    ): SubmitOrderResponse = submitOrder(userId, req)

    // @Timed("matching.submit_order")가 있었지만 submitOrderChecked·submitMarket이 this로 호출해 AOP를 건너뛰어
    // 한 번도 기록된 적이 없었다(대시보드 작업 중 확인). 사가의 matching.saga.submit 타이머가 같은 구간을 잰다.
    fun submitOrder(userId: Long, req: SubmitOrderRequest): SubmitOrderResponse =
        sagaOrchestrator.execute(userId, req)

    fun cancelOrder(userId: Long, orderId: Long): OrderDto {
        // 행 락으로 읽는다 — 같은 주문의 동시 취소가 둘 다 PENDING을 보고 둘 다 환불하지 않도록.
        val order = orderRepo.findWithLockById(orderId) ?: throw NoSuchElementException("주문 없음: $orderId")
        require(order.userId == userId) { "본인의 주문만 취소할 수 있습니다" }

        // 검증을 부수효과(호가창 제거·환불)보다 먼저 한다. 예전엔 환불 후 검증이 실패해 롤백에 기댔다.
        val previous = OrderStates.valueOf(order.status.name)
        val refundAmount = if (order.side == OrderSide.BUY) {
            order.limitPrice?.toMoney(order.remainingQty) ?: Money.ZERO
        } else Money.ZERO
        order.cancel()
        stateMachineService.transition(
            orderId      = order.id,
            currentState = previous,
            event        = OrderEvents.CANCEL,
        )

        orderBookService.cancel(order.stockId, orderId, order.side)

        if (refundAmount > Money.ZERO) {
            jdbc.update("UPDATE paper_accounts SET cash = cash + ?, updated_at = now() WHERE user_id = ?",
                refundAmount.amount, userId)
        }

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
