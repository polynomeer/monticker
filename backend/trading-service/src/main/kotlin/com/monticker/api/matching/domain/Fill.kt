package com.monticker.api.matching.domain

import com.monticker.api.common.domain.Money
import com.monticker.api.common.domain.MoneyConverter
import com.monticker.api.common.domain.Price
import com.monticker.api.common.domain.PriceConverter
import jakarta.persistence.*
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

    @Convert(converter = PriceConverter::class)
    @Column(name = "fill_price", nullable = false, precision = 18, scale = 4)
    val fillPrice: Price,

    @Convert(converter = MoneyConverter::class)
    @Column(nullable = false, precision = 18, scale = 4)
    val amount: Money,

    @Convert(converter = MoneyConverter::class)
    @Column(nullable = false, precision = 18, scale = 4)
    val fee: Money = Money.ZERO,

    @Column(name = "filled_at", nullable = false)
    val filledAt: Instant = Instant.now(),
)
