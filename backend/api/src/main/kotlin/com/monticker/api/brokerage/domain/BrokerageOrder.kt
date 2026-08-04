package com.monticker.api.brokerage.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class BrokerageOrderStatus { SUBMITTED, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED }
enum class OrderSide { BUY, SELL }
enum class OrderType { MARKET, LIMIT }

@Entity
@Table(name = "brokerage_orders")
class BrokerageOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "stock_id")
    val stockId: Long? = null,

    @Column(nullable = false)
    val symbol: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val side: OrderSide,

    @Column(name = "order_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val orderType: OrderType,

    @Column(nullable = false)
    val quantity: Int,

    @Column(name = "limit_price")
    val limitPrice: BigDecimal? = null,

    @Column(name = "filled_qty", nullable = false)
    var filledQty: Int = 0,

    @Column(name = "avg_fill_price")
    var avgFillPrice: BigDecimal? = null,

    @Column(name = "pg_order_id")
    var pgOrderId: String? = null,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: BrokerageOrderStatus = BrokerageOrderStatus.SUBMITTED,

    @Column(name = "reject_reason")
    var rejectReason: String? = null,

    @Column(name = "submitted_at", nullable = false)
    val submittedAt: Instant = Instant.now(),

    @Column(name = "filled_at")
    var filledAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun fill(qty: Int, price: BigDecimal) {
        filledQty      = qty
        avgFillPrice   = price
        status         = BrokerageOrderStatus.FILLED
        filledAt       = Instant.now()
        updatedAt      = Instant.now()
    }

    fun cancel() {
        status    = BrokerageOrderStatus.CANCELLED
        updatedAt = Instant.now()
    }

    fun reject(reason: String) {
        status       = BrokerageOrderStatus.REJECTED
        rejectReason = reason
        updatedAt    = Instant.now()
    }
}
