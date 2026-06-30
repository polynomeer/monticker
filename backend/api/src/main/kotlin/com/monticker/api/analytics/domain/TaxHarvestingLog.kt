package com.monticker.api.analytics.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "tax_harvesting_logs")
class TaxHarvestingLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "simulated_at", nullable = false)
    val simulatedAt: Instant = Instant.now(),

    @Column(name = "realized_gain_ytd", nullable = false)
    val realizedGainYtd: BigDecimal,

    @Column(name = "candidates_json", nullable = false, columnDefinition = "jsonb")
    val candidatesJson: String,

    @Column(name = "estimated_tax_saving")
    val estimatedTaxSaving: BigDecimal? = null,

    @Column(name = "tax_rate_assumed", nullable = false)
    val taxRateAssumed: BigDecimal = BigDecimal("0.22"),
)
