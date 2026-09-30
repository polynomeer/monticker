package com.monticker.api.subscription.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
enum class PgProvider   { MOCK, TOSS, IAMPORT }

@Entity
@Table(name = "payment_records")
class PaymentRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    val plan: SubscriptionPlan,

    @Column(name = "pg_provider", nullable = false)
    @Enumerated(EnumType.STRING)
    val pgProvider: PgProvider = PgProvider.MOCK,

    @Column(name = "pg_transaction_id")
    var pgTransactionId: String? = null,

    /**
     * PG에 보낸 주문 ID. 정기결제는 (구독, 청구주기)에서 결정적으로 유도되므로 재시도해도
     * 같은 값이고, DB 유니크 인덱스(V50)가 같은 주기의 두 번째 청구를 거부한다 (ADR-053).
     * 응답을 못 받았을 때 PG에 "이 건 결제됐나"를 되물을 수 있는 유일한 열쇠이기도 하다.
     */
    @Column(name = "pg_order_id")
    val pgOrderId: String? = null,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Column(nullable = false)
    val currency: String = "KRW",

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.PENDING,

    @Column(name = "failure_reason")
    var failureReason: String? = null,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun markSuccess(pgTransactionId: String) {
        this.pgTransactionId = pgTransactionId
        this.status = PaymentStatus.SUCCESS
        this.paidAt = Instant.now()
    }

    fun markFailed(reason: String) {
        this.failureReason = reason
        this.status = PaymentStatus.FAILED
    }
}
