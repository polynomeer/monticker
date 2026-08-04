package com.monticker.api.brokerage.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class BrokerageSettlementStatus { PENDING, SETTLED }

@Entity
@Table(name = "brokerage_settlements")
class BrokerageSettlement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "order_id")
    val orderId: Long? = null,

    @Column(nullable = false)
    val symbol: String,

    @Column(nullable = false)
    val side: String,

    @Column(nullable = false)
    val quantity: Int,

    @Column(name = "fill_price", nullable = false)
    val fillPrice: BigDecimal,

    @Column(name = "gross_amount", nullable = false)
    val grossAmount: BigDecimal,

    @Column(nullable = false)
    val fee: BigDecimal,

    @Column(nullable = false)
    val tax: BigDecimal,

    @Column(name = "net_amount", nullable = false)
    val netAmount: BigDecimal,

    @Column(name = "settle_date", nullable = false)
    val settleDate: LocalDate,

    @Column(name = "settled_at")
    var settledAt: Instant? = null,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: BrokerageSettlementStatus = BrokerageSettlementStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun settle() {
        status    = BrokerageSettlementStatus.SETTLED
        settledAt = Instant.now()
    }
}
