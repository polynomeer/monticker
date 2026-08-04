package com.monticker.api.settlement.creator.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class EarningStatus { AVAILABLE, PAID_OUT, CANCELLED }

@Entity
@Table(name = "creator_earnings")
class CreatorEarning(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "creator_id", nullable = false)
    val creatorId: Long,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,

    @Column(name = "subscriber_id", nullable = false)
    val subscriberId: Long,

    // nullable — 무료 전략 구독 시 결제 없음
    @Column(name = "payment_id")
    val paymentId: Long? = null,

    @Column(name = "gross_amount", nullable = false)
    val grossAmount: BigDecimal,

    @Column(name = "platform_fee", nullable = false)
    val platformFee: BigDecimal,

    @Column(name = "net_amount", nullable = false)
    val netAmount: BigDecimal,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: EarningStatus = EarningStatus.AVAILABLE,

    @Column(name = "earned_at", nullable = false)
    val earnedAt: Instant = Instant.now(),
)
