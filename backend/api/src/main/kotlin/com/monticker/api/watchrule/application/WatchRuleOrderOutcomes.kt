package com.monticker.api.watchrule.application

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-098 — 지정가 발동(PLACED)의 이후 결과를 발동 기록에 옮긴다: PLACED → FILLED | CANCELLED.
 *
 * **정확히 한 번**은 조건부 UPDATE(`WHERE status = 'PLACED'`)가 보장한다 — 두 번째 전이는 0행이다. 체결과 취소는 주문 행
 * 잠금(스위퍼 `FOR UPDATE SKIP LOCKED`, 취소 `FOR UPDATE`)으로 이미 직렬화돼 둘 중 하나만 일어난다(ADR-074).
 *
 * 두 경로가 있다:
 *  1. [onFilled]·[onCancelled] — 주문 상태를 바꾼 트랜잭션 안에서 동기 리스너([WatchRuleOrderListener])가 부른다.
 *     체결·취소가 롤백되면 이 전이도 롤백된다.
 *  2. [reconcile] — 실행기가 PLACED를 기록한 **직후** 부른다. 주문 접수(사가 트랜잭션)와 발동 기록(실행기의 별도
 *     트랜잭션) 사이에 스위퍼가 체결하거나 사용자가 취소하면, 1의 UPDATE는 아직 커밋되지 않은 PLACED 행을 못 보고 0행으로
 *     끝난다. 그래서 기록 뒤에 주문 행을 `FOR UPDATE`로 잠그고(진행 중인 체결·취소가 커밋될 때까지 기다린다) 커밋된 주문
 *     상태로 같은 전이를 한 번 더 시도한다. 어느 쪽이 먼저든 조건부 UPDATE라 결과는 한 번이다.
 *
 * 잠금 순서는 두 경로 모두 orders → watch_rule_executions라 교착이 없다. 규칙 경유 손익(ADR-085)은 paper_trades에서
 * 계산하므로 이 전이는 손익에 아무것도 더하지 않는다 — 체결 기록(paper_trades)은 체결 리스너가 한 번만 만든다.
 *
 * watchrule은 matching 모듈의 저장소에 의존하지 않고 `orders` 행을 읽기·잠금만 한다(WatchRuleTargets가 watchlist 테이블을
 * 읽는 것과 같은 수준). 주문 행은 바꾸지 않는다.
 */
@Component
class WatchRuleOrderOutcomes(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val filled = registry.counter("watch_rule_executions_total", "status", "filled")
    private val cancelled = registry.counter("watch_rule_executions_total", "status", "cancelled")

    companion object {
        const val FILL_SQL = """
            UPDATE watch_rule_executions SET status = 'FILLED', fill_price = ?, resolved_at = ?
            WHERE order_id = ? AND status = 'PLACED'
        """
        const val CANCEL_SQL = """
            UPDATE watch_rule_executions SET status = 'CANCELLED', reason = ?, resolved_at = ?
            WHERE order_id = ? AND status = 'PLACED'
        """
        /** 커밋된 주문 상태를 잠근 채 읽는다 — 진행 중인 체결·취소 트랜잭션이 끝날 때까지 기다린다. */
        const val LOCK_ORDER_SQL = """
            SELECT status, avg_fill_price, reject_reason, updated_at FROM orders WHERE id = ? FOR UPDATE
        """

        /** 취소 사유가 없을 때(이 필드 추가 전 이벤트·다른 경로)의 기록 문구. */
        const val DEFAULT_CANCEL_REASON = "주문 취소"

        fun cancelReason(reason: String?): String = "지정가 미체결 취소 — ${reason?.takeIf { it.isNotBlank() } ?: DEFAULT_CANCEL_REASON}"
    }

    /** 지정가 체결 — 이 주문의 PLACED 기록을 FILLED로. 바뀐 행 수(0 또는 1). */
    fun onFilled(orderId: Long, fillPrice: BigDecimal, filledAt: Instant): Int {
        val n = jdbc.update(FILL_SQL.trimIndent(), fillPrice, Timestamp.from(filledAt), orderId)
        if (n > 0) {
            filled.increment(n.toDouble())
            log.info("watch rule 지정가 체결 반영 orderId={} price={}", orderId, fillPrice)
        }
        return n
    }

    /** 지정가 취소 — 이 주문의 PLACED 기록을 CANCELLED로. 바뀐 행 수(0 또는 1). */
    fun onCancelled(orderId: Long, reason: String?, cancelledAt: Instant): Int {
        val n = jdbc.update(CANCEL_SQL.trimIndent(), cancelReason(reason), Timestamp.from(cancelledAt), orderId)
        if (n > 0) {
            cancelled.increment(n.toDouble())
            log.info("watch rule 지정가 취소 반영 orderId={} reason={}", orderId, reason)
        }
        return n
    }

    /**
     * PLACED 기록 직후의 대조 — 주문 행을 잠그고 이미 체결·취소됐으면 같은 전이를 한다. 주문이 아직 미체결이면 아무것도
     * 하지 않는다(이후 결과는 리스너가 옮긴다 — 이 트랜잭션이 커밋된 뒤에는 PLACED 행이 보인다).
     */
    fun reconcile(orderId: Long): Int = tx.execute {
        val order = jdbc.query(LOCK_ORDER_SQL.trimIndent(), { rs, _ ->
            LockedOrder(
                status = rs.getString("status"),
                avgFillPrice = rs.getBigDecimal("avg_fill_price"),
                rejectReason = rs.getString("reject_reason"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }, orderId).firstOrNull() ?: return@execute 0
        when (order.status) {
            "FILLED" -> order.avgFillPrice?.let { onFilled(orderId, it, order.updatedAt) } ?: 0
            "CANCELLED", "REJECTED" -> onCancelled(orderId, order.rejectReason, order.updatedAt)
            else -> 0
        }
    } ?: 0

    private data class LockedOrder(val status: String, val avgFillPrice: BigDecimal?, val rejectReason: String?, val updatedAt: Instant)
}
