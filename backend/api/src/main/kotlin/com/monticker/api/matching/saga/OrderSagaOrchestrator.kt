package com.monticker.api.matching.saga

import com.monticker.api.common.aop.Timed
import com.monticker.api.common.domain.Money
import com.monticker.api.common.domain.Price
import com.monticker.api.matching.application.FillQueryService
import com.monticker.api.matching.application.MatchingOrderBookService
import com.monticker.api.matching.application.SubmitOrderRequest
import com.monticker.api.matching.application.SubmitOrderResponse
import com.monticker.api.matching.domain.*
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.statemachine.OrderEvents
import com.monticker.api.matching.statemachine.OrderStateMachineService
import com.monticker.api.matching.statemachine.OrderStates
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Order Saga 오케스트레이터.
 *
 * 주문 흐름을 단계별 트랜잭션으로 분해하고, 실패 시 보상 트랜잭션(Compensation)을 순서대로 실행한다.
 *
 * 단계:
 *   INIT → VALIDATED → CASH_RESERVED → ORDER_CREATED → ORDER_FILLED → CASH_SETTLED → COMPLETED
 *
 * 보상 순서 (역순):
 *   ORDER_FILLED → 미체결 주문이면 취소
 *   CASH_RESERVED → 예약된 현금 환불
 *   ORDER_CREATED → 주문 상태 CANCELLED로 변경
 */
@Service
class OrderSagaOrchestrator(
    private val sagaRepo: OrderSagaRepository,
    private val orderRepo: OrderRepository,
    private val fillRepo: FillRepository,
    private val fillQueryService: FillQueryService,
    private val orderBookService: MatchingOrderBookService,
    private val stateMachineService: OrderStateMachineService,
    private val eventPublisher: ApplicationEventPublisher,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Timed("matching.saga.submit", tags = ["module=saga"])
    @Transactional
    fun execute(userId: Long, req: SubmitOrderRequest): SubmitOrderResponse {
        val saga = sagaRepo.save(OrderSaga(
            userId   = userId,
            stockId  = req.stockId,
            side     = req.side,
            quantity = req.quantity,
        ))
        log.debug("[Saga:{}] 시작 userId={} stockId={} side={} qty={}",
            saga.id, userId, req.stockId, req.side, req.quantity)

        return try {
            val response = runSteps(saga, userId, req)
            saga.status = SagaStatus.COMPLETED
            saga.currentStep = SagaStep.COMPLETED
            saga.completedAt = Instant.now()
            sagaRepo.save(saga)
            response
        } catch (ex: Exception) {
            log.warn("[Saga:{}] 실패 step={} — 보상 트랜잭션 시작", saga.id, saga.currentStep, ex)
            compensate(saga)
            throw ex
        }
    }

    // ── 정방향 단계 ──────────────────────────────────────────────────────────

    private fun runSteps(saga: OrderSaga, userId: Long, req: SubmitOrderRequest): SubmitOrderResponse {
        // STEP 1: VALIDATE
        saga.currentStep = SagaStep.VALIDATED
        require(req.quantity > 0) { "수량은 1 이상이어야 합니다" }
        require(req.side in listOf("BUY", "SELL")) { "side는 BUY 또는 SELL이어야 합니다" }
        require(req.orderType in listOf("MARKET", "LIMIT")) { "orderType은 MARKET 또는 LIMIT이어야 합니다" }
        val limitPrice = req.limitPrice?.let { Price.of(it) }
        if (req.orderType == "LIMIT") require(limitPrice != null) { "LIMIT 주문에는 limit_price가 필요합니다" }
        val stockExists = jdbc.queryForObject("SELECT COUNT(*) FROM stocks WHERE id = ?", Long::class.java, req.stockId) ?: 0L
        require(stockExists > 0) { "존재하지 않는 종목: stockId=${req.stockId}" }

        val currentPrice = getCurrentPrice(req.stockId)
        val estimatedPrice = limitPrice ?: currentPrice

        // STEP 2: RESERVE_CASH (BUY 전용)
        saga.currentStep = SagaStep.CASH_RESERVED
        val reserveAmount: BigDecimal? = if (req.side == "BUY") {
            val cash = getAccountCash(userId)
            val toReserve = estimatedPrice.toMoney(req.quantity)
            require(cash >= toReserve) { "잔고 부족: 필요 $toReserve, 보유 $cash" }
            adjustCash(userId, toReserve.amount.negate())
            saga.reservedAmount = toReserve.amount
            toReserve.amount
        } else null

        // STEP 3: CREATE_ORDER
        saga.currentStep = SagaStep.ORDER_CREATED
        val order = orderRepo.save(Order(
            userId    = userId,
            stockId   = req.stockId,
            side      = OrderSide.valueOf(req.side),
            orderType = OrderType.valueOf(req.orderType),
            quantity  = req.quantity,
            limitPrice = limitPrice,
            status    = OrderStatus.PENDING,
        ))
        saga.orderId = order.id

        // STEP 4: FILL_ORDER (조건 충족 시 즉시 체결)
        saga.currentStep = SagaStep.ORDER_FILLED
        val fillPrice: Price? = when {
            req.orderType == "MARKET" -> currentPrice
            req.side == "BUY"  && limitPrice!! >= currentPrice -> currentPrice
            req.side == "SELL" && limitPrice!! <= currentPrice -> currentPrice
            else -> null
        }

        val fills = mutableListOf<com.monticker.api.matching.application.FillDto>()
        if (fillPrice != null) {
            val fillAmount = fillPrice.toMoney(req.quantity)
            val fill = fillRepo.save(Fill(
                orderId   = order.id,
                userId    = userId,
                stockId   = req.stockId,
                side      = req.side,
                quantity  = req.quantity,
                fillPrice = fillPrice,
                amount    = fillAmount,
                fee       = Money.ZERO,
            ))
            fills.add(fill.toFillDto())

            stateMachineService.transition(
                orderId      = order.id,
                currentState = OrderStates.PENDING,
                event        = OrderEvents.COMPLETE_FILL,
            )
            order.fill(req.quantity, fillPrice)
            orderRepo.save(order)

            // STEP 5: SETTLE_CASH
            saga.currentStep = SagaStep.CASH_SETTLED
            if (req.side == "BUY") {
                val refund = (reserveAmount ?: BigDecimal.ZERO) - fillAmount.amount
                if (refund > BigDecimal.ZERO) adjustCash(userId, refund)
            } else {
                adjustCash(userId, fillAmount.amount)
            }

            eventPublisher.publishEvent(OrderFilledEvent(
                orderId   = order.id,
                userId    = userId,
                stockId   = req.stockId,
                fillId    = fill.id,
                side      = req.side,
                quantity  = req.quantity,
                fillPrice = fillPrice.amount,
                amount    = fillAmount.amount,
            ))
        } else {
            orderBookService.submit(order)
            saga.currentStep = SagaStep.CASH_SETTLED  // 미체결은 정산 없음; 단계 진행
        }

        return SubmitOrderResponse(
            order   = order.toOrderDto(),
            fills   = fills,
            message = if (fills.isNotEmpty()) "주문 체결 완료" else "주문 접수 완료 (미체결)",
        )
    }

    // ── 보상 트랜잭션 ─────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun compensate(saga: OrderSaga) {
        saga.status = SagaStatus.COMPENSATING
        saga.errorMessage = saga.errorMessage ?: "단계 ${saga.currentStep}에서 실패"
        runCatching { sagaRepo.save(saga) }

        try {
            when {
                saga.currentStep >= SagaStep.ORDER_FILLED -> compensateFill(saga)
                saga.currentStep >= SagaStep.ORDER_CREATED -> compensateOrder(saga)
                saga.currentStep >= SagaStep.CASH_RESERVED -> compensateCash(saga)
            }
            saga.status = SagaStatus.COMPENSATED
            saga.compensatedAt = Instant.now()
        } catch (ex: Exception) {
            log.error("[Saga:{}] 보상 트랜잭션 실패 — 수동 검토 필요", saga.id, ex)
            saga.status = SagaStatus.FAILED
            saga.errorMessage = ex.message
        }
        sagaRepo.save(saga)
    }

    private fun compensateFill(saga: OrderSaga) {
        val orderId = saga.orderId ?: return
        val order = orderRepo.findById(orderId).orElse(null) ?: return
        if (order.status == OrderStatus.PENDING || order.status == OrderStatus.PARTIALLY_FILLED) {
            runCatching { orderBookService.cancel(order.stockId, orderId, order.side) }
            order.cancel()
            orderRepo.save(order)
            log.info("[Saga:{}] 보상: 미체결 주문 취소 orderId={}", saga.id, orderId)
        }
    }

    private fun compensateOrder(saga: OrderSaga) {
        val orderId = saga.orderId ?: return
        val order = orderRepo.findById(orderId).orElse(null) ?: return
        if (order.status != OrderStatus.CANCELLED) {
            order.cancel()
            orderRepo.save(order)
            log.info("[Saga:{}] 보상: 주문 취소 orderId={}", saga.id, orderId)
        }
    }

    private fun compensateCash(saga: OrderSaga) {
        val reserved = saga.reservedAmount ?: return
        adjustCash(saga.userId, reserved)
        log.info("[Saga:{}] 보상: 현금 환불 userId={} amount={}", saga.id, saga.userId, reserved)
    }

    // ── 복구 스케줄러 ─────────────────────────────────────────────────────────

    /**
     * 기동 후 5분 경과한 미완료 사가를 주기적으로 탐색해 보상 트랜잭션을 재시도한다.
     * 장애/재시작 후 중간 상태 사가가 영구적으로 걸리지 않도록 방지.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    @Transactional
    fun recoverIncomplete() {
        val stale = sagaRepo.findIncomplete(Instant.now().minusSeconds(300))
        if (stale.isEmpty()) return
        log.warn("[SagaRecovery] 미완료 사가 {}건 발견 — 보상 시작", stale.size)
        stale.forEach { saga ->
            log.warn("[SagaRecovery] sagaId={} step={} status={}", saga.id, saga.currentStep, saga.status)
            compensate(saga)
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun getCurrentPrice(stockId: Long): Price =
        jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId,
        )?.let { Price.of(it) } ?: throw IllegalStateException("현재가 조회 불가: stockId=$stockId")

    private fun getAccountCash(userId: Long): Money =
        jdbc.queryForObject("SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId)
            ?.let { Money.of(it) } ?: run {
            jdbc.update(
                "INSERT INTO paper_accounts (user_id, cash, created_at, updated_at) VALUES (?, 10000000, now(), now()) ON CONFLICT (user_id) DO NOTHING",
                userId
            )
            Money.INITIAL_BALANCE
        }

    private fun adjustCash(userId: Long, delta: BigDecimal) {
        jdbc.update("UPDATE paper_accounts SET cash = cash + ?, updated_at = now() WHERE user_id = ?", delta, userId)
    }

    private fun Fill.toFillDto() = com.monticker.api.matching.application.FillDto(
        id        = id,
        orderId   = orderId,
        stockId   = stockId,
        side      = side,
        quantity  = quantity,
        fillPrice = fillPrice.amount,
        amount    = amount.amount,
        fee       = fee.amount,
        filledAt  = filledAt,
    )

    private fun Order.toOrderDto() = com.monticker.api.matching.application.OrderDto(
        id           = id,
        stockId      = stockId,
        side         = side.name,
        orderType    = orderType.name,
        quantity     = quantity,
        limitPrice   = limitPrice?.amount,
        filledQty    = filledQty,
        avgFillPrice = avgFillPrice?.amount,
        status       = status.name,
        rejectReason = rejectReason,
        createdAt    = createdAt,
    )
}
