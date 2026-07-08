package com.monticker.api.quant.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "quant_backtest_results")
class QuantBacktestResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "rule_set_id", nullable = false)
    val ruleSetId: Long,

    @Column(name = "rule_set_version", nullable = false)
    val ruleSetVersion: Int,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    val endDate: LocalDate,

    @Column(name = "initial_capital", nullable = false)
    val initialCapital: BigDecimal,

    @Column(name = "final_capital", nullable = false)
    val finalCapital: BigDecimal,

    @Column(name = "total_return")
    val totalReturn: BigDecimal? = null,

    @Column(name = "annual_return")
    val annualReturn: BigDecimal? = null,

    @Column(name = "mdd")
    val mdd: BigDecimal? = null,

    @Column(name = "win_rate")
    val winRate: BigDecimal? = null,

    @Column(name = "profit_factor")
    val profitFactor: BigDecimal? = null,

    @Column(name = "trade_count")
    val tradeCount: Int? = null,

    @Column(name = "avg_holding_days")
    val avgHoldingDays: BigDecimal? = null,

    @Column(name = "benchmark_return")
    val benchmarkReturn: BigDecimal? = null,

    @Column(name = "excess_return")
    val excessReturn: BigDecimal? = null,

    @Column(name = "commission_rate", nullable = false)
    val commissionRate: BigDecimal = BigDecimal("0.015"),

    @Column(name = "slippage_rate", nullable = false)
    val slippageRate: BigDecimal = BigDecimal("0.1"),

    @Column(name = "reliability_score", length = 1)
    val reliabilityScore: String? = null,

    @Column(name = "reliability_notes", columnDefinition = "jsonb")
    val reliabilityNotes: String? = null,

    @Column(name = "trades_json", columnDefinition = "jsonb")
    val tradesJson: String? = null,

    @Column(name = "equity_curve_json", columnDefinition = "jsonb")
    val equityCurveJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
