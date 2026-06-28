package com.monticker.api.quant.domain

import java.math.BigDecimal
import java.time.LocalDate

data class DailyCandle(
    val date: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class MacdValue(
    val macdLine: Double,
    val signalLine: Double,
    val histogram: Double,
)

data class BollingerBands(
    val upper: Double,
    val middle: Double,
    val lower: Double,
)

// Rule DSL types
data class RuleCondition(
    val indicator: String,
    val comparator: String,
    val params: Map<String, Any> = emptyMap(),
    val value: Any? = null,           // Double or List<Double>
)

data class RuleGroup(
    val operator: String,             // "AND" | "OR"
    val conditions: List<RuleCondition>,
)

data class PositionSizing(
    val type: String,
    val value: Double,
)

data class RuleDefinition(
    val entryRules: RuleGroup,
    val exitRules: RuleGroup,
    val positionSizing: PositionSizing,
)

// Backtest result types
data class QuantTradeRecord(
    val entryDate: LocalDate,
    val exitDate: LocalDate,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Int,
    val pnl: Double,
    val pnlPct: Double,
    val exitReason: String,
)

data class QuantEquityPoint(
    val date: LocalDate,
    val equity: Double,
    val drawdown: Double,
)

data class QuantBacktestMetrics(
    val totalReturn: Double,
    val annualReturn: Double,
    val mdd: Double,
    val winRate: Double,
    val profitFactor: Double,
    val tradeCount: Int,
    val avgHoldingDays: Double,
    val benchmarkReturn: Double,
    val excessReturn: Double,
    val reliabilityScore: String,
    val reliabilityNotes: Map<String, Any>,
)

data class QuantBacktestRunResult(
    val initialCapital: Double,
    val finalCapital: Double,
    val metrics: QuantBacktestMetrics,
    val trades: List<QuantTradeRecord>,
    val equityCurve: List<QuantEquityPoint>,
)
