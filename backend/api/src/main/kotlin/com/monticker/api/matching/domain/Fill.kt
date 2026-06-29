package com.monticker.api.matching.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "fills")
class Fill(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "order_id", nullable = false)
    val orderId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false, length = 4)
    val side: String,

    @Column(nullable = false)
    val quantity: Int,

    @Column(name = "fill_price", nullable = false, precision = 18, scale = 4)
    val fillPrice: BigDecimal,

    @Column(nullable = false, precision = 18, scale = 4)
    val amount: BigDecimal,

    @Column(nullable = false, precision = 18, scale = 4)
    val fee: BigDecimal = BigDecimal.ZERO,

    @Column(name = "filled_at", nullable = false)
    val filledAt: Instant = Instant.now(),
)
