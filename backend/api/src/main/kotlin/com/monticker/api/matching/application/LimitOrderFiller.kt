package com.monticker.api.matching.application

import com.monticker.api.common.domain.CandleFreshness
import com.monticker.api.common.domain.LatestClose
import com.monticker.api.common.domain.Money
import com.monticker.api.common.domain.Price
import com.monticker.api.matching.domain.Fill
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderStatus
import com.monticker.api.matching.domain.OrderType
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.statemachine.OrderEvents
import com.monticker.api.matching.statemachine.OrderStateMachineService
import com.monticker.api.matching.statemachine.OrderStates
import com.monticker.api.risk.application.RiskCheckerService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** 미체결 지정가 한 건의 처리 결과. */
enum class LimitFillOutcome { FILLED, NOT_CROSSED, SKIPPED, REJECTED }

/**
 * ADR-074 — 미체결 LIMIT 주문 한 건을 현재가로 체결한다. 주문 하나 = 트랜잭션 하나.
 *
 * 동시성: 주문 행을 `FOR UPDATE SKIP LOCKED`로 잡는다. 여러 pod의 스위퍼가 같은 후보를 동시에 보더라도
 * 한 pod만 행을 얻고, 나머지는 건너뛴다(대기하지 않는다). 사용자의 취소(`findWithLockById`, FOR UPDATE)와도
 * 같은 행 락으로 직렬화되므로 "취소 환불 + 체결"이 둘 다 일어나지 않는다 — 락을 얻은 뒤 상태를 다시 본다.
 *
 * 돈: BUY는 제출 때 `limit_price × 잔량`을 예약했으므로 체결가(≤ 지정가)와의 차액만 돌려준다. SELL은 체결 대금을
 * 더한다. 체결 기록은 사가와 같은 OrderFilledEvent → paper.PaperExecutionListener(동기, 같은 트랜잭션)로 남는다.
 *
 * 리스크(ADR-074 Note): BUY는 체결 직전에 잔량 × 체결가로 리스크 게이트를 다시 돈다(주문 자신은 대기 매수에서 뺀다). 제출 이후
 * 다른 매수·체결·손실로 한도를 넘게 됐으면 체결하지 않고 취소하며 예약금 전액을 돌려준다 — 사용자 취소와 같은 이벤트·환불이다.
 */
@Service
class LimitOrderFiller(
    private val orderRepo: OrderRepository,
    private val fillRepo: FillRepository,
    private val stateMachineService: OrderStateMachineService,
    private val eventPublisher: ApplicationEventPublisher,
    private val jdbc: JdbcTemplate,
    private val orderBookService: MatchingOrderBookService,
    private val riskChecker: RiskCheckerService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val LOCK_SQL =
            "SELECT id FROM orders WHERE id = ? AND order_type = 'LIMIT' AND status IN ('PENDING', 'PARTIALLY_FILLED') FOR UPDATE SKIP LOCKED"
        const val LATEST_PRICE_SQL = "SELECT close, candle_time FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1"
    }

    @Transactional
    fun fillIfCrossed(orderId: Long): LimitFillOutcome {
        val locked = jdbc.query(LOCK_SQL, { rs, _ -> rs.getLong("id") }, orderId).firstOrNull()
            ?: return LimitFillOutcome.SKIPPED   // 다른 pod가 처리 중이거나 이미 체결·취소됨
        val order = orderRepo.findById(locked).orElse(null) ?: return LimitFillOutcome.SKIPPED
        if (order.orderType != OrderType.LIMIT ||
            (order.status != OrderStatus.PENDING && order.status != OrderStatus.PARTIALLY_FILLED)
        ) return LimitFillOutcome.SKIPPED

        val limit = order.limitPrice ?: return LimitFillOutcome.SKIPPED
        // 오래된 봉(시세 단절·장 마감 후)으로는 체결하지 않는다 — 이번 주기를 건너뛴다(CandleFreshness).
        val current = jdbc.query(LATEST_PRICE_SQL, { rs, _ ->
            LatestClose(rs.getBigDecimal("close"), rs.getTimestamp("candle_time").toInstant())
        }, order.stockId)
            .firstOrNull()?.takeIf { it.close > BigDecimal.ZERO && CandleFreshness.isFresh(it.candleTime) }
            ?.let { Price.of(it.close) }
            ?: return LimitFillOutcome.NOT_CROSSED
        val crossed = if (order.side == OrderSide.BUY) limit >= current else limit <= current
        if (!crossed) return LimitFillOutcome.NOT_CROSSED

        val qty = order.remainingQty
        if (order.side == OrderSide.BUY) {
            val risk = riskChecker.checkPaperFill(order.userId, order.stockId, qty, current.amount, order.id)
            if (!risk.approved) {
                cancelForRisk(order.id, risk.blockedBy ?: "Unknown risk rule")
                return LimitFillOutcome.REJECTED
            }
        }
        if (order.side == OrderSide.SELL) {
            // 제출 때 미체결 매도 잔량까지 빼고 판정했으므로 정상 흐름에선 항상 충분하다. 계좌 초기화 등으로
            // 포지션이 사라졌다면 체결하지 않고 거절한다 — 공매도를 만들지 않는다(ADR-047 §5).
            val held = jdbc.query(
                "SELECT net_qty FROM portfolio_positions WHERE user_id = ? AND stock_id = ? FOR UPDATE",
                { rs, _ -> rs.getInt("net_qty") }, order.userId, order.stockId,
            ).firstOrNull() ?: 0
            if (held < qty) {
                rejectForMissingPosition(order.id, held, qty)
                return LimitFillOutcome.REJECTED
            }
        }

        val fillAmount = current.toMoney(qty)
        val fill = fillRepo.save(Fill(
            orderId = order.id, userId = order.userId, stockId = order.stockId, side = order.side.name,
            quantity = qty, fillPrice = current, amount = fillAmount, fee = Money.ZERO,
        ))
        stateMachineService.transition(
            orderId = order.id,
            currentState = OrderStates.valueOf(order.status.name),
            event = OrderEvents.COMPLETE_FILL,
        )
        order.fill(qty, current)
        orderRepo.save(order)
        // 힙 호가창은 표시용 사본이다(이 pod 것만 지워진다 — ADR-048). 체결 근거는 위의 DB 행이다.
        runCatching { orderBookService.cancel(order.stockId, order.id, order.side) }

        if (order.side == OrderSide.BUY) {
            val refund = limit.toMoney(qty).amount - fillAmount.amount
            if (refund > BigDecimal.ZERO) adjustCash(order.userId, refund)
        } else {
            adjustCash(order.userId, fillAmount.amount)
        }

        eventPublisher.publishEvent(OrderFilledEvent(
            orderId = order.id, userId = order.userId, stockId = order.stockId, fillId = fill.id,
            side = order.side.name, quantity = qty, fillPrice = current.amount, amount = fillAmount.amount,
            filledAt = fill.filledAt,
            // ADR-085 — 제출 때 주문 행에 남긴 출처. V81 이전에 접수된 미체결 지정가는 V82가 백필했다.
            origin = order.origin?.name, originRef = order.originRef,
        ))
        log.info("[LimitSweep] filled orderId={} {} {}x{} (limit {})", order.id, order.side, qty, current, limit)
        return LimitFillOutcome.FILLED
    }

    /** SELL은 예약금이 없으므로 환불도 없다. 취소 이벤트로 알리되(refund 0) 원장에는 아무것도 남지 않는다. */
    private fun rejectForMissingPosition(orderId: Long, held: Int, qty: Int) {
        val order = orderRepo.findById(orderId).orElseThrow()
        val previous = OrderStates.valueOf(order.status.name)
        order.cancel()
        order.rejectReason = "체결 시점 보유 수량 부족: 보유 $held, 잔량 $qty"
        stateMachineService.transition(orderId = order.id, currentState = previous, event = OrderEvents.CANCEL)
        orderRepo.save(order)
        runCatching { orderBookService.cancel(order.stockId, order.id, order.side) }
        eventPublisher.publishEvent(OrderCancelledEvent(
            orderId = order.id, userId = order.userId, stockId = order.stockId, side = order.side.name,
            refundAmount = BigDecimal.ZERO,
        ))
        log.warn("[LimitSweep] SELL orderId={} cancelled — position missing (held={}, qty={})", order.id, held, qty)
    }

    /** 체결 시점 리스크 차단 — 사용자 취소(MatchingService.cancelOrder)와 같은 상태 전이·예약금 환불·취소 이벤트. */
    private fun cancelForRisk(orderId: Long, blockedBy: String) {
        val order = orderRepo.findById(orderId).orElseThrow()
        val previous = OrderStates.valueOf(order.status.name)
        val refund = order.limitPrice?.toMoney(order.remainingQty)?.amount ?: BigDecimal.ZERO
        order.cancel()
        order.rejectReason = "체결 시점 리스크 한도 초과: $blockedBy"
        stateMachineService.transition(orderId = order.id, currentState = previous, event = OrderEvents.CANCEL)
        orderRepo.save(order)
        runCatching { orderBookService.cancel(order.stockId, order.id, order.side) }
        if (refund > BigDecimal.ZERO) adjustCash(order.userId, refund)
        eventPublisher.publishEvent(OrderCancelledEvent(
            orderId = order.id, userId = order.userId, stockId = order.stockId, side = order.side.name,
            refundAmount = refund,
        ))
        log.warn("[LimitSweep] BUY orderId={} cancelled at fill — risk blocked by {}", order.id, blockedBy)
    }

    private fun adjustCash(userId: Long, delta: BigDecimal) {
        jdbc.update("UPDATE paper_accounts SET cash = cash + ?, updated_at = now() WHERE user_id = ?", delta, userId)
    }
}
