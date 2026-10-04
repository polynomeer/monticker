package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import io.micrometer.core.instrument.MeterRegistry
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
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ADR-055 — 실시세 틱만 비동기 큐에 넣는다. condition은 @Async 디스패치 전에 평가되므로 합성(Mock) 틱
    // 폭주가 conditionalOrderExecutor 큐(200)를 채워 실시세 틱을 밀어내지 않는다.
    @EventListener(condition = "#event.provenance.source.real")
    @Async("conditionalOrderExecutor")
    fun onTick(event: MarketTickReceivedEvent) {
        val tick = event.tick
        // 실시세라도 정규장·신선한 틱으로만 실주문을 낸다. DB 조회 전에 거른다.
        event.provenance.rejectReasonForRealOrder(Instant.now())?.let { reason ->
            meterRegistry.counter("conditional_order_tick_ignored_total", "reason", reason.substringBefore('=')).increment()
            return
        }
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
                // ADR-056 — 결정적 식별자. 발동 중 크래시하면 리퍼가 이 값으로 주문 행을 찾는다(없으면 미전송 확정).
                clientOrderId = clientOrderIdFor(row.id),
            )
            when {
                order.status == BrokerageOrderStatus.REJECTED -> {
                    markFailed(row.id, order.rejectReason ?: "증권사 거부", order.id)
                    log.warn("[ConditionalOrderEvaluator] 증권사 거부: id={} userId={} reason={}", row.id, row.userId, order.rejectReason)
                }
                order.status.isUnresolved -> keepTriggered(row, order.id)
                else -> {
                    jdbc.update(
                        "UPDATE conditional_orders SET status = 'EXECUTED', executed_order_id = ?, updated_at = ? WHERE id = ?",
                        order.id, Timestamp.from(Instant.now()), row.id,
                    )
                    log.info("[ConditionalOrderEvaluator] 발동: id={} userId={} symbol={} orderId={}", row.id, row.userId, row.symbol, order.id)
                }
            }
            row.ocoGroupId?.let { cancelOcoSiblings(it, row.id) }
        } catch (e: OrderOutcomeUnknownException) {
            // 의도는 커밋됐고 결과를 기록하지 못했다 — 주문이 나갔을 수 있다. FAILED로 단정하지 않는다.
            keepTriggered(row, e.orderId)
            row.ocoGroupId?.let { cancelOcoSiblings(it, row.id) }
        } catch (e: Exception) {
            // ADR-032 — 실패 시 재시도하지 않는다(조건을 계속 만족하는 동안 매 틱마다
            // 재시도하면 같은 실패 요청이 반복 발사될 수 있다). 사용자가 재등록해야 한다.
            // ADR-056 — OrderOutcomeUnknownException이 아닌 예외는 의도 커밋 전에 났다 → 증권사 호출이 없었다.
            markFailed(row.id, e.message?.take(500) ?: "알 수 없는 오류")
            log.warn("[ConditionalOrderEvaluator] 발동 실패: id={} userId={} reason={}", row.id, row.userId, e.message)
            row.ocoGroupId?.let { cancelOcoSiblings(it, row.id) }
        }
    }

    /** ADR-056 — 주문 결과를 모른다. TRIGGERED로 두고 주문만 연결한다 — 대조 잡이 주문을 해소하면 리퍼가 따라간다. */
    private fun keepTriggered(row: ConditionalOrderRow, orderId: Long) {
        jdbc.update(
            "UPDATE conditional_orders SET executed_order_id = ?, updated_at = ? WHERE id = ?",
            orderId, Timestamp.from(Instant.now()), row.id,
        )
        log.warn("[ConditionalOrderEvaluator] 발동 — 주문 결과 확인 중: id={} userId={} orderId={}", row.id, row.userId, orderId)
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

    companion object {
        /** ADR-056 — 조건부 주문 하나에 증권사 주문은 최대 하나다(client_order_id 유니크 인덱스). */
        fun clientOrderIdFor(conditionalOrderId: Long) = "co-$conditionalOrderId"
    }
}
