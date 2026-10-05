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
    /** ADR-079 — exitRules의 AND/OR와 무관하게 항상 먼저 보는 강제 청산 */
    val hardExits: HardExits = HardExits(),
)

/**
 * ruleDefinition.hardExits — { maxHoldDays?, trailingStopPct? }.
 * - maxHoldDays: 진입일 다음 거래일부터 센 보유 거래일 수가 이 값에 닿으면 그날 종가에 청산
 * - trailingStopPct: 진입일 이후 최고 종가 대비 이 % 이상 내려오면 그날 종가에 청산
 */
data class HardExits(
    val maxHoldDays: Int? = null,
    val trailingStopPct: Double? = null,
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
    /** ADR-079 — 연환산 샤프(무위험 3%, 단순 백테스트 엔진과 같은 가정). 일별 수익률이 2개 미만이거나 변동이 0이면 null */
    val sharpe: Double?,
    val reliabilityScore: String,
    val reliabilityNotes: Map<String, Any>,
)

// ── ADR-079 보조 데이터(뉴스 감성·공시) ─────────────────────────────────────────────

/** 하루(이용 가능일 기준)에 모인 뉴스 감성 건수 — news_articles.sentiment(POSITIVE/NEGATIVE/NEUTRAL) */
data class SentimentCount(val positive: Int = 0, val negative: Int = 0, val neutral: Int = 0) {
    val total: Int get() = positive + negative + neutral
    operator fun plus(o: SentimentCount) = SentimentCount(positive + o.positive, negative + o.negative, neutral + o.neutral)
}

/**
 * 일봉 외 지표 입력. 키는 "이용 가능일"이다 — 장 마감(KST 15:30) 이후에 나온 뉴스·공시는 그날 종가
 * 판단에 쓸 수 없으므로 다음 날로 넘긴다(미래 정보 누수 방지).
 */
data class QuantAuxData(
    val sentimentByDate: Map<LocalDate, SentimentCount> = emptyMap(),
    val disclosuresByDate: Map<LocalDate, Set<String>> = emptyMap(),
) {
    companion object {
        val EMPTY = QuantAuxData()
    }
}

data class QuantBacktestRunResult(
    val initialCapital: Double,
    val finalCapital: Double,
    val metrics: QuantBacktestMetrics,
    val trades: List<QuantTradeRecord>,
    val equityCurve: List<QuantEquityPoint>,
)
