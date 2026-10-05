package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 실거래 주문의 결과를 모르게 됐을 때와 그 결과가 확정됐을 때 사용자에게 알린다(ADR-056 위에 ADR-065 경로).
 *
 * 결과 불명은 대개 증권사 장애 중에 생긴다. 사용자는 "실패했다"고 생각해 다시 누르기 쉽다 — 그게 이중 주문의 가장 흔한 경로다.
 * 그래서 화면을 열지 않아도 "다시 내지 말라"를 바로 알리고, 확정되면 결과를 다시 알린다. 방해 금지 시간에도 보낸다.
 *
 * 반드시 상태를 바꾸는 트랜잭션 안에서 호출한다 — 커밋돼야 외부화(notify.user)된다. 커밋이 롤백되면 알림도 없다.
 */
@Component
class OrderOutcomeNotices(private val events: ApplicationEventPublisher) {

    fun unknown(order: BrokerageOrder) = events.publishEvent(
        UserNotificationCommand(
            userId = order.userId,
            category = NotificationCategory.ORDER_OUTCOME,
            title = "${order.symbol} ${sideLabel(order.side)} 주문 결과를 확인 중입니다",
            body = "증권사 응답이 없어 주문이 들어갔는지 아직 모릅니다. 같은 주문을 다시 내지 마세요 — 이중 주문이 될 수 있습니다. " +
                "확인되는 대로 다시 알려드립니다.",
            dedupKey = "brokerage-order-unknown:${order.id}",
            data = mapOf("type" to "ORDER_OUTCOME_UNKNOWN", "orderId" to order.id, "symbol" to order.symbol),
        ),
    )

    /** 대조 잡·수동 확정이 결과를 확정했다. */
    fun resolved(order: BrokerageOrder) = events.publishEvent(
        UserNotificationCommand(
            userId = order.userId,
            category = NotificationCategory.ORDER_OUTCOME,
            title = "${order.symbol} ${sideLabel(order.side)} 주문 결과가 확인됐습니다",
            body = when (order.status) {
                BrokerageOrderStatus.FILLED -> "주문이 체결됐습니다(${order.filledQty}주)."
                BrokerageOrderStatus.PARTIALLY_FILLED -> "주문이 일부 체결됐습니다(${order.filledQty}/${order.quantity}주)."
                BrokerageOrderStatus.SUBMITTED -> "주문이 증권사에 접수돼 있습니다. 체결되면 반영됩니다."
                BrokerageOrderStatus.CANCELLED -> "주문이 취소된 상태로 확인됐습니다."
                BrokerageOrderStatus.REJECTED -> "주문이 증권사에 들어가지 않은 것으로 확인됐습니다. 필요하면 다시 주문할 수 있습니다."
                else -> "주문 상태: ${order.status}"
            },
            dedupKey = "brokerage-order-resolved:${order.id}",
            data = mapOf("type" to "ORDER_OUTCOME_RESOLVED", "orderId" to order.id, "symbol" to order.symbol, "status" to order.status.name),
        ),
    )

    /** 자동 대조로 고를 수 없어 운영자가 확인한다 — 처음 한 번만. */
    fun needsReview(order: BrokerageOrder) = events.publishEvent(
        UserNotificationCommand(
            userId = order.userId,
            category = NotificationCategory.ORDER_OUTCOME,
            title = "${order.symbol} ${sideLabel(order.side)} 주문을 직접 확인하고 있습니다",
            body = "증권사 주문 목록에서 자동으로 짝을 찾지 못해 담당자가 확인 중입니다. 확인 전까지 같은 주문을 다시 내지 마세요.",
            dedupKey = "brokerage-order-review:${order.id}",
            data = mapOf("type" to "ORDER_NEEDS_REVIEW", "orderId" to order.id, "symbol" to order.symbol),
        ),
    )

    /**
     * ADR-082 — 실거래 체결(끌 수 있다: 알림 설정 "체결·정산"). 결과 불명 해소로 체결이 확인된 경우는 [resolved]가 이미 알리므로
     * 부르지 않는다. 체결 하나에 한 번(dedupKey = 주문 id) — 체결을 반영하는 경로가 여럿이어도 행 락 아래 한 번만 FILLED가 된다(ADR-061).
     */
    fun filled(order: BrokerageOrder) = events.publishEvent(
        UserNotificationCommand(
            userId = order.userId,
            category = NotificationCategory.FILLS,
            title = "${order.symbol} ${sideLabel(order.side)} 체결",
            body = if (order.filledQty in 1 until order.quantity)
                "${order.filledQty}/${order.quantity}주가 평균 ${won(order.avgFillPrice)}원에 체결됐습니다(잔량 취소)."
            else "${order.filledQty}주가 평균 ${won(order.avgFillPrice)}원에 체결됐습니다.",
            dedupKey = "brokerage-order-filled:${order.id}",
            data = mapOf("type" to "ORDER_FILLED", "orderId" to order.id, "symbol" to order.symbol),
        ),
    )

    /** ADR-082 — 실거래 정산 완료(T+2). 정산 하나에 한 번. */
    fun settled(settlement: BrokerageSettlement) = events.publishEvent(
        UserNotificationCommand(
            userId = settlement.userId,
            category = NotificationCategory.FILLS,
            title = "${settlement.symbol} ${if (settlement.side == "BUY") "매수" else "매도"} 정산 완료",
            body = "${settlement.quantity}주 정산이 끝났습니다. 정산 금액 ${won(settlement.netAmount)}원.",
            dedupKey = "brokerage-settled:${settlement.id}",
            data = mapOf("type" to "SETTLED", "settlementId" to settlement.id, "symbol" to settlement.symbol),
        ),
    )

    private fun won(v: java.math.BigDecimal?) = v?.let { "%,d".format(it.setScale(0, java.math.RoundingMode.HALF_UP).toLong()) } ?: "—"

    private fun sideLabel(side: OrderSide) = if (side == OrderSide.BUY) "매수" else "매도"
}
