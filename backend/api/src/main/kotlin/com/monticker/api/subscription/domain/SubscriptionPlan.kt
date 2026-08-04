package com.monticker.api.subscription.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class PlanCode { FREE, PRO, QUANT }

@Entity
@Table(name = "subscription_plans")
class SubscriptionPlan(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    @Enumerated(EnumType.STRING)
    val code: PlanCode,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val price: BigDecimal,

    @Column(nullable = false)
    val currency: String = "KRW",

    @Column(columnDefinition = "jsonb", nullable = false)
    val features: String = "[]",

    @Column(nullable = false)
    val isActive: Boolean = true,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)
