package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-056 — 발동(TRIGGERED) 뒤 결말이 기록되지 않은 조건부 주문을 정리한다.
 *
 * 근거: [BrokerageService.submitOrder]는 주문 행(client_order_id = co-<id>)을 **커밋한 뒤에만** 증권사를 부른다.
 * 그래서 행이 없으면 증권사 호출이 없었다고 단정할 수 있다. 행이 있으면 그 주문의 상태를 따라간다 — 결과 불명이면
 * 대조 잡이 주문을 해소할 때까지 기다린다.
 *
 * 이 보장은 V51 이후 발동분에만 성립한다(그 전에는 co- 식별자가 없었다). 그 전 TRIGGERED 행은 건드리지 않는다.
 */
@Component
class ConditionalOrderReaper(
    private val jdbc: JdbcTemplate,
    private val failures: ConditionalOrderFailures,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    fun reapStuckTriggered() {
        val stuck = jdbc.queryForList(
            """
            SELECT id FROM conditional_orders
            WHERE status = 'TRIGGERED'
              AND triggered_at < now() - interval '2 minutes'
              AND triggered_at > (SELECT installed_on FROM flyway_schema_history WHERE version = '51')
            ORDER BY triggered_at
            LIMIT 50
            """.trimIndent(),
            Long::class.java,
        )
        stuck.forEach { reap(it) }
    }

    internal fun reap(conditionalOrderId: Long) {
        val order = jdbc.query(
            "SELECT id, status FROM brokerage_orders WHERE client_order_id = ?",
            { rs, _ -> rs.getLong("id") to BrokerageOrderStatus.valueOf(rs.getString("status")) },
            ConditionalOrderEvaluator.clientOrderIdFor(conditionalOrderId),
        ).firstOrNull()

        val reason = when {
            order == null -> "발동 중 중단 — 주문 미전송 확인"
            order.second.isUnresolved -> return   // 대조 잡이 주문을 해소하면 다음 주기에 따라간다
            order.second == BrokerageOrderStatus.REJECTED || order.second == BrokerageOrderStatus.CANCELLED ->
                "주문 ${order.second}"
            else -> null
        }
        // ADR-065 — FAILED는 사용자에게 알린다(보호가 사라졌다).
        val updated = if (reason != null) {
            failures.markFailed(conditionalOrderId, reason, order?.first)
        } else {
            jdbc.update(
                "UPDATE conditional_orders SET status = 'EXECUTED', executed_order_id = ?, updated_at = ? WHERE id = ? AND status = 'TRIGGERED'",
                order!!.first, Timestamp.from(Instant.now()), conditionalOrderId,
            ) == 1
        }
        if (updated) {
            log.warn("[ConditionalOrderReaper] TRIGGERED 정리: id={} → {} (orderId={}, reason={})",
                conditionalOrderId, if (reason != null) "FAILED" else "EXECUTED", order?.first, reason)
        }
    }
}
