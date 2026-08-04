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
