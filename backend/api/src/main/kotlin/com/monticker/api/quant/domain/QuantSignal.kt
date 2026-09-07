package com.monticker.api.quant.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

enum class SignalDirection { BUY, SELL }

@Entity
@Table(name = "quant_signals")
class QuantSignal(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "forward_test_id")
    val forwardTestId: Long?,

    @Column(name = "rule_set_id", nullable = false, length = 24)
    val ruleSetId: String,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val direction: SignalDirection,

    @Column(name = "signal_time", nullable = false)
    val signalTime: Instant,

    @Column(name = "eval_date")
    val evalDate: LocalDate?,

    @Column(nullable = false)
    val mode: String = "FORWARD_TEST",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
