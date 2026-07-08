package com.monticker.api.analytics.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "regime_history")
class RegimeHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "stock_id")
    val stockId: Long? = null,

    @Column(name = "market", length = 20)
    val market: String? = null,

    @Column(name = "regime_date", nullable = false)
    val regimeDate: LocalDate,

    @Column(name = "regime", nullable = false, length = 20)
    val regime: String,

    @Column(name = "adx")
    val adx: BigDecimal? = null,

    @Column(name = "volatility")
    val volatility: BigDecimal? = null,

    @Column(name = "trend_slope")
    val trendSlope: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
