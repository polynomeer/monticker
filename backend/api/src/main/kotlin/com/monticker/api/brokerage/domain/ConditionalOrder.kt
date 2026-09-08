package com.monticker.api.brokerage.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ConditionalTriggerType {
    STOP_LOSS, TAKE_PROFIT, PRICE_ABOVE, PRICE_BELOW;

    /**
     * ADR-032 — 4가지 타입은 UX/주문 내역 가독성을 위한 라벨이고, 평가 로직은 비교
     * 방향 두 가지만 본다: STOP_LOSS/PRICE_BELOW = 현재가 <= trigger, TAKE_PROFIT/
     * PRICE_ABOVE = 현재가 >= trigger.
     */
    fun isTriggered(currentPrice: BigDecimal, triggerPrice: BigDecimal): Boolean = when (this) {
        STOP_LOSS, PRICE_BELOW  -> currentPrice <= triggerPrice
        TAKE_PROFIT, PRICE_ABOVE -> currentPrice >= triggerPrice
    }
}

enum class ConditionalOrderStatus { ACTIVE, TRIGGERED, EXECUTED, CANCELLED, EXPIRED, FAILED }

@Entity
@Table(name = "conditional_orders")
class ConditionalOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false)
    val symbol: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val side: OrderSide,

    @Column(name = "trigger_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val triggerType: ConditionalTriggerType,

    @Column(name = "trigger_price", nullable = false)
    val triggerPrice: BigDecimal,

    @Column(name = "order_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val orderType: OrderType,

    @Column(name = "limit_price")
    val limitPrice: BigDecimal? = null,

    @Column(nullable = false)
    val quantity: Int,

    @Column(name = "oco_group_id")
    var ocoGroupId: UUID? = null,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ConditionalOrderStatus = ConditionalOrderStatus.ACTIVE,

    @Column(name = "fail_reason")
    var failReason: String? = null,

    @Column(name = "executed_order_id")
    var executedOrderId: Long? = null,

    @Column(name = "expires_at")
    val expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "triggered_at")
    var triggeredAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun cancel() {
        status = ConditionalOrderStatus.CANCELLED
        updatedAt = Instant.now()
    }
}
