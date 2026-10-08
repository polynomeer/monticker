package com.monticker.api.matching.domain

import com.monticker.api.common.domain.Price
import com.monticker.api.common.domain.PriceConverter
import com.monticker.api.matching.submit.OrderOriginType
import jakarta.persistence.*
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

    @Convert(converter = PriceConverter::class)
    @Column(name = "limit_price", precision = 18, scale = 4)
    val limitPrice: Price? = null,

    @Column(name = "filled_qty", nullable = false)
    var filledQty: Int = 0,

    @Convert(converter = PriceConverter::class)
    @Column(name = "avg_fill_price", precision = 18, scale = 4)
    var avgFillPrice: Price? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.PENDING,

    @Column(name = "reject_reason")
    var rejectReason: String? = null,

    /**
     * ADR-051 — 서버 내부에서 발행된 주문의 멱등 키(예: watch rule은 "WR:{ruleId}:{eventId}").
     * 부분 유니크 인덱스(V48)가 걸려 있어 같은 키로 두 번 INSERT 되지 않는다. 사용자가 화면에서
     * 직접 낸 주문은 null이며 ADR-007의 `X-Idempotency-Key` 필터가 대신 보호한다.
     */
    @Column(name = "idempotency_key", length = 100)
    val idempotencyKey: String? = null,

    /** ADR-085 — 진입 출처. 사가가 제출 경로에서 받은 값을 그대로 저장한다. V81 이전 주문은 V82 백필, 판정 불가면 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 20)
    val origin: OrderOriginType? = null,

    /** ADR-085 — 출처 ref(watch_rules.id · paper_conditional_orders.id · 룰셋 id). MANUAL이면 null. */
    @Column(name = "origin_ref")
    val originRef: Long? = null,

    /** ADR-091 — 접수 시점 최우선 호가(실시간 호가만). 없으면 null이고 슬리피지 집계에서 빠진다. */
    @Column(name = "quote_bid", precision = 18, scale = 4)
    val quoteBid: java.math.BigDecimal? = null,

    @Column(name = "quote_ask", precision = 18, scale = 4)
    val quoteAsk: java.math.BigDecimal? = null,

    @Column(name = "quote_at")
    val quoteAt: Instant? = null,

    @Column(name = "quote_source", length = 20)
    val quoteSource: String? = null,

    /** ADR-091 — 사가 진입 시각(엔진 지연의 시작점). V88 이전 주문은 null. */
    @Column(name = "submitted_at")
    val submittedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val remainingQty: Int get() = quantity - filledQty

    fun fill(qty: Int, price: Price) {
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
