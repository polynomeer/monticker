package com.monticker.api.quant.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "quant_forward_test_equity")
class QuantForwardTestEquityPoint(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "forward_test_id", nullable = false)
    val forwardTestId: Long,

    @Column(name = "eval_date", nullable = false)
    val evalDate: LocalDate,

    @Column(nullable = false)
    val equity: BigDecimal,

    @Column(nullable = false)
    val drawdown: BigDecimal,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
