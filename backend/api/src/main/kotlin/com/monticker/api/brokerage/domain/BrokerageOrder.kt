package com.monticker.api.brokerage.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-056 — PENDING_SUBMIT: 의도를 브로커 호출 전에 커밋했다(호출 결과 미기록).
 * UNKNOWN: 요청은 나갔는데 확정 응답이 없다 — 증권사에서 체결됐을 수 있다. 둘 다 대조 잡이 해소한다.
 */
enum class BrokerageOrderStatus {
    PENDING_SUBMIT, SUBMITTED, UNKNOWN, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED;

    /** 증권사에서의 상태를 아직 모른다 — 같은 종목·방향 새 주문을 막고, 대조 잡이 본다. */
    val isUnresolved: Boolean get() = this == PENDING_SUBMIT || this == UNKNOWN
}

enum class OrderResolution { BROKER_LOOKUP, NOT_FOUND, MANUAL }
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

    // 증권사 주문번호만으로는 취소 호출이 불가능한 프로바이더를 위한 추가 참조값
    // (KIS의 KRX_FWDG_ORD_ORGNO/지점코드). Toss/Mock은 사용하지 않는다(null).
    @Column(name = "broker_order_ref")
    var brokerOrderRef: String? = null,

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

    // ADR-056 — 우리가 만든 주문 식별자(Toss clientOrderId). 조건부 주문은 co-<id>로 결정적이다.
    @Column(name = "client_order_id")
    val clientOrderId: String? = null,

    @Column(name = "reconcile_attempts", nullable = false)
    var reconcileAttempts: Int = 0,

    @Column(name = "needs_review", nullable = false)
    var needsReview: Boolean = false,

    @Column(name = "resolved_by")
    @Enumerated(EnumType.STRING)
    var resolvedBy: OrderResolution? = null,

    @Column(name = "next_reconcile_at")
    var nextReconcileAt: Instant? = null,

    // 관리자 수동 확정(resolved_by = MANUAL) — 누가·왜.
    @Column(name = "resolved_by_user")
    var resolvedByUser: Long? = null,

    @Column(name = "resolution_note")
    var resolutionNote: String? = null,

    // ADR-061 — 동기화 잡이 마지막으로 증권사에 상태를 물어본 시각.
    @Column(name = "status_synced_at")
    var statusSyncedAt: Instant? = null,
) {
    fun markSubmitted(pgOrderId: String, brokerOrderRef: String?) {
        this.pgOrderId      = pgOrderId
        this.brokerOrderRef = brokerOrderRef
        status              = BrokerageOrderStatus.SUBMITTED
        updatedAt           = Instant.now()
    }

    fun markUnknown(reason: String) {
        status       = BrokerageOrderStatus.UNKNOWN
        rejectReason = reason   // 불명 사유도 같은 칸에 남긴다 — 해소되면 덮어쓴다
        updatedAt    = Instant.now()
    }

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
