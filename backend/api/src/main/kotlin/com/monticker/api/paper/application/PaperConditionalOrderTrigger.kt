package com.monticker.api.paper.application

import com.monticker.api.common.domain.CandleFreshness
import com.monticker.api.common.domain.LatestClose
import com.monticker.api.matching.submit.OrderOrigin
import com.monticker.api.matching.submit.OrderSubmitter
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

enum class PaperConditionalOutcome { EXECUTED, NOT_TRIGGERED, SKIPPED }

/**
 * ADR-075 — 조건부 주문 한 건의 발동. 트랜잭션 하나 안에서: 행 락(SKIP LOCKED) → 조건 재확인 →
 * matching::submit 시장가 주문(@RiskChecked, 멱등 키 `PCO:{id}`) → EXECUTED → 같은 OCO 그룹 취소.
 *
 * 주문이 거부되면(리스크 한도·보유 부족) 예외가 이 트랜잭션을 롤백시킨다 — 실패 기록은 [markFailed]가 별도 트랜잭션으로 남긴다.
 */
@Service
class PaperConditionalOrderFirer(
    private val jdbc: JdbcTemplate,
    private val orderSubmitter: OrderSubmitter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val LOCK_SQL = "SELECT id, user_id, stock_id, side, trigger_type, trigger_price, quantity, oco_group_id " +
            "FROM paper_conditional_orders WHERE id = ? AND status = 'ACTIVE' FOR UPDATE SKIP LOCKED"
        const val LATEST_PRICE_SQL = "SELECT close, candle_time FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1"
    }

    private data class Row(
        val id: Long, val userId: Long, val stockId: Long, val side: String,
        val triggerType: PaperTriggerType, val triggerPrice: BigDecimal, val quantity: Int, val ocoGroupId: java.util.UUID?,
    )

    @Transactional
    fun fire(id: Long): PaperConditionalOutcome {
        val row = jdbc.query(LOCK_SQL, { rs, _ ->
            Row(
                rs.getLong("id"), rs.getLong("user_id"), rs.getLong("stock_id"), rs.getString("side"),
                PaperTriggerType.valueOf(rs.getString("trigger_type")), rs.getBigDecimal("trigger_price"),
                rs.getInt("quantity"), rs.getObject("oco_group_id", java.util.UUID::class.java),
            )
        }, id).firstOrNull() ?: return PaperConditionalOutcome.SKIPPED   // 다른 pod가 처리 중이거나 이미 취소·발동됨

        // 오래된 봉(시세 단절·장 마감 후)으로는 발동하지 않는다 — 이번 주기를 건너뛴다(CandleFreshness).
        val price = jdbc.query(LATEST_PRICE_SQL, { rs, _ ->
            LatestClose(rs.getBigDecimal("close"), rs.getTimestamp("candle_time").toInstant())
        }, row.stockId).firstOrNull()?.takeIf { CandleFreshness.isFresh(it.candleTime) }?.close
            ?: return PaperConditionalOutcome.NOT_TRIGGERED
        if (!row.triggerType.isTriggered(price, row.triggerPrice)) return PaperConditionalOutcome.NOT_TRIGGERED

        // ADR-051 멱등 키 — 같은 조건부 주문으로 두 번 체결되지 않는다(행 락과 함께 이중 방어).
        val result = orderSubmitter.submitMarket(
            row.userId, row.stockId, row.side, row.quantity,
            origin = OrderOrigin.conditional(row.id),   // ADR-085
            idempotencyKey = "PCO:${row.id}",
        )

        jdbc.update(
            "UPDATE paper_conditional_orders SET status = 'EXECUTED', executed_order_id = ?, triggered_at = now(), updated_at = now() WHERE id = ?",
            result.orderId, row.id,
        )
        row.ocoGroupId?.let { group ->
            jdbc.update(
                "UPDATE paper_conditional_orders SET status = 'CANCELLED', fail_reason = 'OCO 상대 체결', updated_at = now() " +
                    "WHERE oco_group_id = ? AND id <> ? AND status IN ('ACTIVE', 'WAITING_PARENT')",
                group, row.id,
            )
        }
        log.info("[PaperConditional] executed id={} {} {} x{} at {} (trigger {} {})",
            row.id, row.side, row.stockId, row.quantity, result.fillPrice, row.triggerType, row.triggerPrice)
        return PaperConditionalOutcome.EXECUTED
    }

    /**
     * 발동했지만 주문이 거부됐다 — 재시도하지 않고 FAILED로 남긴다(같은 조건이 3초마다 같은 거부를 반복하지 않게).
     * OCO 상대는 살려 둔다: 손절이 리스크 한도로 막혔다고 익절까지 지울 이유는 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(id: Long, reason: String) {
        jdbc.update(
            "UPDATE paper_conditional_orders SET status = 'FAILED', fail_reason = ?, triggered_at = now(), updated_at = now() " +
                "WHERE id = ? AND status = 'ACTIVE'",
            reason.take(500), id,
        )
    }
}

/**
 * ADR-075 — 조건부 주문 스위퍼. [com.monticker.api.matching.application.LimitOrderSweeper]와 같은 모양:
 * 락 없이 넓게 후보를 고르고, 주문별 트랜잭션([PaperConditionalOrderFirer.fire])에서 다시 판정한다.
 */
@Component
class PaperConditionalOrderTrigger(
    private val jdbc: JdbcTemplate,
    private val firer: PaperConditionalOrderFirer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CANDIDATES_SQL = """
            SELECT co.id FROM paper_conditional_orders co
            JOIN LATERAL (
                SELECT c.close FROM candles_1m c
                WHERE c.stock_id = co.stock_id AND c.candle_time >= now() - interval '${CandleFreshness.MAX_AGE_SQL}'
                ORDER BY c.candle_time DESC LIMIT 1
            ) p ON true
            WHERE co.status = 'ACTIVE'
              AND (((co.trigger_type IN ('TAKE_PROFIT', 'PRICE_ABOVE')) AND p.close >= co.trigger_price)
                OR ((co.trigger_type IN ('STOP_LOSS', 'PRICE_BELOW')) AND p.close <= co.trigger_price))
            ORDER BY co.created_at
            LIMIT 200
        """
    }

    @Scheduled(fixedDelayString = "\${app.paper.conditional-sweep.interval-ms:3000}", initialDelay = 25_000)
    fun sweep(): Int {
        val ids = jdbc.query(CANDIDATES_SQL.trimIndent(), { rs, _ -> rs.getLong("id") })
        var executed = 0
        for (id in ids) {
            try {
                if (firer.fire(id) == PaperConditionalOutcome.EXECUTED) executed++
            } catch (e: Exception) {
                // 리스크 한도(422)·보유 부족(400) 등 — 주문 트랜잭션은 롤백됐다. 사유만 남긴다(사용자 데이터·키는 로그에 없다).
                log.warn("[PaperConditional] id={} rejected: {}", id, e.message)
                runCatching { firer.markFailed(id, e.message ?: e.javaClass.simpleName) }
                    .onFailure { log.error("[PaperConditional] id={} could not be marked FAILED", id, it) }
            }
        }
        return executed
    }
}
