package com.monticker.api.matching.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class OrderSide { BUY, SELL }
enum class OrderType { MARKET, LIMIT }
enum class OrderStatus { PENDING, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

@Entity
@Table(name = "orders")
class Order(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    val side: OrderSide,

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    val orderType: OrderType,

    @Column(nullable = false)
    val quantity: Int,

    @Column(name = "limit_price", precision = 18, scale = 4)
    val limitPrice: BigDecimal? = null,

    @Column(name = "filled_qty", nullable = false)
    var filledQty: Int = 0,

    @Column(name = "avg_fill_price", precision = 18, scale = 4)
    var avgFillPrice: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.PENDING,

    @Column(name = "reject_reason")
    var rejectReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val remainingQty: Int get() = quantity - filledQty

    fun fill(qty: Int, price: BigDecimal) {
        require(status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED) {
            "체결 불가 상태: $status"
        }
        require(qty > 0 && qty <= remainingQty) { "유효하지 않은 체결 수량: $qty (잔여: $remainingQty)" }
        filledQty += qty
        avgFillPrice = price
        status = if (remainingQty == 0) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED
        updatedAt = Instant.now()
    }

    fun cancel() {
        require(status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED) {
            "취소 불가 상태: $status"
        }
        status = OrderStatus.CANCELLED
        updatedAt = Instant.now()
    }

    fun reject(reason: String) {
        require(status == OrderStatus.PENDING) { "거절 불가 상태: $status" }
        status = OrderStatus.REJECTED
        rejectReason = reason
        updatedAt = Instant.now()
    }
}
