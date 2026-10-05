package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.common.notification.UserNotificationCommand
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-065 — 발동한 조건부 주문을 FAILED로 닫고, 같은 트랜잭션에서 사용자 알림을 남긴다.
 *
 * 발동 실패는 재시도하지 않으므로(ADR-032) FAILED는 곧 "걸어 둔 보호가 사라졌다"는 뜻이다. 사용자는 화면을 열기 전까지
 * 스탑로스가 아직 걸려 있다고 믿는다 — 그래서 실패를 기록하는 모든 경로(평가기·리퍼)가 이 한 곳을 거친다.
 * UPDATE는 `status = 'TRIGGERED'`일 때만 한다: 평가기와 리퍼가 같은 행을 닫더라도 알림은 한 번이다.
 */
@Component
class ConditionalOrderFailures(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val events: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    /** @return 이 호출이 TRIGGERED → FAILED로 바꿨으면 true */
    fun markFailed(conditionalOrderId: Long, reason: String, executedOrderId: Long? = null): Boolean =
        tx.execute {
            val closed = jdbc.query(
                """
                UPDATE conditional_orders SET status = 'FAILED', fail_reason = ?, executed_order_id = ?, updated_at = ?
                WHERE id = ? AND status = 'TRIGGERED'
                RETURNING user_id, symbol, trigger_type
                """.trimIndent(),
                { rs, _ -> Closed(rs.getLong("user_id"), rs.getString("symbol"), ConditionalTriggerType.valueOf(rs.getString("trigger_type"))) },
                reason, executedOrderId, Timestamp.from(Instant.now()), conditionalOrderId,
            ).firstOrNull() ?: return@execute false

            events.publishEvent(notification(conditionalOrderId, closed, reason))
            meterRegistry.counter("conditional_order_failed_total", "trigger_type", closed.triggerType.name).increment()
            true
        } ?: false

    private data class Closed(val userId: Long, val symbol: String, val triggerType: ConditionalTriggerType)

    private fun notification(id: Long, closed: Closed, reason: String): UserNotificationCommand {
        val label = when (closed.triggerType) {
            ConditionalTriggerType.STOP_LOSS -> "손절(스탑로스)"
            ConditionalTriggerType.TAKE_PROFIT -> "익절"
            ConditionalTriggerType.PRICE_ABOVE, ConditionalTriggerType.PRICE_BELOW -> "조건부"
        }
        return UserNotificationCommand(
            userId = closed.userId,
            title = "${closed.symbol} $label 주문이 실행되지 않았습니다",
            body = "조건이 충족돼 발동했지만 주문이 나가지 않았습니다: $reason. 이 주문은 더 이상 걸려 있지 않습니다 — 확인 후 다시 등록해주세요.",
            dedupKey = "conditional-order-failed:$id",
            data = mapOf("type" to "CONDITIONAL_ORDER_FAILED", "conditionalOrderId" to id, "symbol" to closed.symbol),
        )
    }
}
