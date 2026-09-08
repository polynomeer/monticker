package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ConditionalOrderRow(
    val id: Long,
    val userId: Long,
    val symbol: String,
    val side: OrderSide,
    val triggerType: ConditionalTriggerType,
    val triggerPrice: BigDecimal,
    val orderType: String,
    val limitPrice: BigDecimal?,
    val quantity: Int,
    val ocoGroupId: UUID?,
)

/**
 * ADR-032 — 조건부 주문 평가·발동. AlertEvaluator(backend/worker)와 같은 모양이지만
 * (틱마다 해당 stockId의 대상만 조회), 발동은 "알림 전송"이 아니라 "실제 브로커 주문
 * 제출"이라 원자적 UPDATE로 정확히 한 번만 발동하게 만든다.
 */
@Component
class ConditionalOrderEvaluator(
    private val jdbc: JdbcTemplate,
    private val brokerageService: BrokerageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Async("conditionalOrderExecutor")
    fun onTick(event: MarketTickReceivedEvent) {
        val tick = event.tick
        try {
            for (row in fetchActiveForStock(tick.stockId)) {
                if (row.triggerType.isTriggered(tick.price, row.triggerPrice)) {
                    fire(row)
                }
            }
        } catch (e: Exception) {
            log.error("[ConditionalOrderEvaluator] stockId={} 평가 오류: {}", tick.stockId, e.message)
        }
    }

    private fun fetchActiveForStock(stockId: Long): List<ConditionalOrderRow> =
        jdbc.query(
            """
            SELECT id, user_id, symbol, side, trigger_type, trigger_price, order_type, limit_price, quantity, oco_group_id
            FROM conditional_orders
            WHERE stock_id = ? AND status = 'ACTIVE'
            """,
            { rs, _ ->
                ConditionalOrderRow(
                    id = rs.getLong("id"),
                    userId = rs.getLong("user_id"),
                    symbol = rs.getString("symbol"),
                    side = OrderSide.valueOf(rs.getString("side")),
                    triggerType = ConditionalTriggerType.valueOf(rs.getString("trigger_type")),
                    triggerPrice = rs.getBigDecimal("trigger_price"),
                    orderType = rs.getString("order_type"),
                    limitPrice = rs.getBigDecimal("limit_price"),
                    quantity = rs.getInt("quantity"),
                    ocoGroupId = rs.getString("oco_group_id")?.let { UUID.fromString(it) },
                )
            },
            stockId,
        )

    private fun fire(row: ConditionalOrderRow) {
        // ADR-032 — 여러 커넥션/중복 이벤트가 동시에 들어와도 정확히 한 번만 발동하도록
        // 조건부 UPDATE의 영향 행 수로 원자성을 확보한다. 실제 돈이 나가는 행동이라
        // AlertEvaluator의 Redis 쿨다운(알림 중복 방지)보다 강한 보장이 필요해 DB
        // 트랜잭션의 원자적 업데이트를 쓴다.
        val claimed = jdbc.update(
            "UPDATE conditional_orders SET status = 'TRIGGERED', triggered_at = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), row.id,
        )
        if (claimed != 1) return  // 다른 스레드가 이미 가져갔거나 그 사이 취소됨

        try {
            val order = brokerageService.submitOrder(
                row.userId,
                BrokerageOrderRequest(
                    symbol = row.symbol,
                    side = row.side.name,
                    orderType = row.orderType,
                    quantity = row.quantity,
                    limitPrice = row.limitPrice,
                ),
            )
            if (order.status == BrokerageOrderStatus.REJECTED) {
                markFailed(row.id, order.rejectReason ?: "증권사 거부", order.id)
                log.warn("[ConditionalOrderEvaluator] 증권사 거부: id={} userId={} reason={}", row.id, row.userId, order.rejectReason)
            } else {
                jdbc.update(
                    "UPDATE conditional_orders SET status = 'EXECUTED', executed_order_id = ?, updated_at = ? WHERE id = ?",
                    order.id, Timestamp.from(Instant.now()), row.id,
                )
                log.info("[ConditionalOrderEvaluator] 발동: id={} userId={} symbol={} orderId={}", row.id, row.userId, row.symbol, order.id)
            }
            row.ocoGroupId?.let { cancelOcoSiblings(it, row.id) }
        } catch (e: Exception) {
            // ADR-032 — 실패 시 재시도하지 않는다(조건을 계속 만족하는 동안 매 틱마다
            // 재시도하면 같은 실패 요청이 반복 발사될 수 있다). 사용자가 재등록해야 한다.
            markFailed(row.id, e.message?.take(500) ?: "알 수 없는 오류")
            log.warn("[ConditionalOrderEvaluator] 발동 실패: id={} userId={} reason={}", row.id, row.userId, e.message)
            row.ocoGroupId?.let { cancelOcoSiblings(it, row.id) }
        }
    }

    private fun markFailed(id: Long, reason: String, executedOrderId: Long? = null) {
        jdbc.update(
            "UPDATE conditional_orders SET status = 'FAILED', fail_reason = ?, executed_order_id = ?, updated_at = ? WHERE id = ?",
            reason, executedOrderId, Timestamp.from(Instant.now()), id,
        )
    }

    private fun cancelOcoSiblings(groupId: UUID, executedId: Long) {
        val cancelled = jdbc.update(
            "UPDATE conditional_orders SET status = 'CANCELLED', updated_at = ? WHERE oco_group_id = ? AND id != ? AND status = 'ACTIVE'",
            Timestamp.from(Instant.now()), groupId, executedId,
        )
        if (cancelled > 0) log.info("[ConditionalOrderEvaluator] OCO 형제 취소: groupId={} count={}", groupId, cancelled)
    }
}
