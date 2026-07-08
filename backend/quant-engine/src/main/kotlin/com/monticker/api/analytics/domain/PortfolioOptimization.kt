package com.monticker.api.analytics.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "portfolio_optimizations")
class PortfolioOptimization(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "target_return")
    val targetReturn: BigDecimal? = null,

    @Column(name = "universe_json", nullable = false, columnDefinition = "jsonb")
    val universeJson: String,

    @Column(name = "weights_json", nullable = false, columnDefinition = "jsonb")
    val weightsJson: String,

    @Column(name = "expected_return")
    val expectedReturn: BigDecimal? = null,

    @Column(name = "expected_risk")
    val expectedRisk: BigDecimal? = null,

    @Column(name = "frontier_json", columnDefinition = "jsonb")
    val frontierJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
