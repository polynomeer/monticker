package com.monticker.api.settlement.creator.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class PayoutStatus { REQUESTED, APPROVED, REJECTED, PAID }

@Entity
@Table(name = "creator_payouts")
class CreatorPayout(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "creator_id", nullable = false)
    val creatorId: Long,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Column(name = "bank_name")
    val bankName: String? = null,

    @Column(name = "account_number")
    val accountNumber: String? = null,

    @Column(name = "account_holder")
    val accountHolder: String? = null,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: PayoutStatus = PayoutStatus.REQUESTED,

    @Column(name = "reject_reason")
    var rejectReason: String? = null,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,
) {
    fun approve() {
        status = PayoutStatus.APPROVED
        processedAt = Instant.now()
    }

    fun reject(reason: String) {
        status = PayoutStatus.REJECTED
        rejectReason = reason
        processedAt = Instant.now()
    }

    fun markPaid() {
        status = PayoutStatus.PAID
        processedAt = Instant.now()
    }
}
