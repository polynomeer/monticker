package com.monticker.api.paper.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class SettlementStatus { PENDING, SETTLED, FAILED }

@Entity
@Table(name = "paper_settlements")
class PaperSettlement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "trade_id", nullable = false, unique = true)
    val tradeId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

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

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: SettlementStatus = SettlementStatus.PENDING,

    @Column(name = "settle_date", nullable = false)
    val settleDate: LocalDate,

    @Column(name = "settled_at")
    var settledAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun settle() {
        status = SettlementStatus.SETTLED
        settledAt = Instant.now()
    }

    fun fail() {
        status = SettlementStatus.FAILED
    }
}
