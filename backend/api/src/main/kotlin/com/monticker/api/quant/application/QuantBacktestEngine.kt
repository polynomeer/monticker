package com.monticker.api.quant.application

import com.monticker.api.quant.domain.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

object QuantBacktestEngine {

    // ForwardTestService가 동일한 수수료/슬리피지 가정으로 시뮬레이션 체결하기 위해 internal로 공유한다.
    internal const val COMMISSION_RATE = 0.00015   // 0.015%
    internal const val SLIPPAGE_RATE   = 0.001     // 0.1%

    fun run(
        candles: List<DailyCandle>,
        ruleDef: RuleDefinition,
        initialCapital: Double,
        fromDate: java.time.LocalDate,
        toDate: java.time.LocalDate,
        aux: QuantAuxData = QuantAuxData.EMPTY,
    ): QuantBacktestRunResult {
        val filtered = candles
            .filter { it.date >= fromDate && it.date <= toDate }
            .sortedBy { it.date }

        if (filtered.isEmpty()) {
            return emptyResult(initialCapital)
        }

        var cash        = initialCapital
        var position: SimPosition? = null
        val trades      = mutableListOf<QuantTradeRecord>()
        val equity      = mutableListOf<QuantEquityPoint>()
        var peakEquity  = initialCapital

        // ADR-078 — 하루 판단은 QuantDayStep 한 곳에서만 한다(포워드 테스트와 동일 경로).
        for ((idx, candle) in filtered.withIndex()) {
            val price = candle.close.toDouble()

            when (val action = QuantDayStep.decide(ruleDef, filtered, idx, cash, position, aux)) {
                is DayAction.Exit -> {
                    val pos = position!!
                    trades.add(closedTrade(pos, candle.date, action.fillPrice, action.proceeds, action.reason))
                    cash += action.proceeds
                    position = null
                }
                is DayAction.Enter -> {
                    position = SimPosition(action.qty, action.fillPrice, candle.date)
                    cash -= action.cost
                }
                DayAction.Hold -> {}
            }

            val totalEquity = cash + (position?.qty ?: 0) * price
            peakEquity = max(peakEquity, totalEquity)
            val drawdown = if (peakEquity > 0) (peakEquity - totalEquity) / peakEquity * 100 else 0.0
            equity.add(QuantEquityPoint(candle.date, totalEquity, drawdown))
        }

        // Force-close last position
        position?.let { pos ->
            val last       = filtered.last()
            val exitPrice  = last.close.toDouble() * (1 - SLIPPAGE_RATE)
            val commission = pos.qty * exitPrice * COMMISSION_RATE
            val proceeds   = pos.qty * exitPrice - commission
            trades.add(closedTrade(pos, last.date, exitPrice, proceeds, "END"))
            cash += proceeds
        }

        val finalCapital = cash
        val metrics = calcMetrics(initialCapital, finalCapital, trades, equity, filtered)
        return QuantBacktestRunResult(initialCapital, finalCapital, metrics, trades, equity)
    }

    private fun closedTrade(pos: SimPosition, exitDate: java.time.LocalDate, exitPrice: Double, proceeds: Double, reason: String) =
        QuantTradeRecord(
            entryDate  = pos.entryDate,
            exitDate   = exitDate,
            entryPrice = pos.entryPrice,
            exitPrice  = exitPrice,
            quantity   = pos.qty,
            pnl        = proceeds - pos.qty * pos.entryPrice,
            pnlPct     = (exitPrice - pos.entryPrice) / pos.entryPrice * 100,
            exitReason = reason,
        )

    private fun calcMetrics(
        initial: Double,
        final: Double,
        trades: List<QuantTradeRecord>,
        equity: List<QuantEquityPoint>,
        candles: List<DailyCandle>,
    ): QuantBacktestMetrics {
        val totalReturn = (final - initial) / initial * 100
        val daysRange   = candles.size
        val years       = daysRange / 252.0
        val annualReturn = if (years > 0) ((final / initial).pow(1.0 / years) - 1) * 100 else totalReturn

        val mdd = equity.maxOfOrNull { it.drawdown } ?: 0.0

        val wins        = trades.count { it.pnl > 0 }
        val winRate     = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100 else 0.0
        val totalProfit = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
        val totalLoss   = trades.filter { it.pnl < 0 }.sumOf { abs(it.pnl) }
        val profitFactor = when {
            totalLoss > 0  -> totalProfit / totalLoss
            totalProfit > 0 -> 99.9
            else            -> 0.0
        }
        val avgHoldingDays = if (trades.isNotEmpty())
            trades.map { it.entryDate.until(it.exitDate, java.time.temporal.ChronoUnit.DAYS).toDouble() }.average()
        else 0.0

        // Benchmark: buy-and-hold
        val benchmarkReturn = if (candles.size >= 2) {
            val startPrice = candles.first().open.toDouble()
            val endPrice   = candles.last().close.toDouble()
            if (startPrice > 0) (endPrice - startPrice) / startPrice * 100 else 0.0
        } else 0.0

        val excessReturn = totalReturn - benchmarkReturn

        // Reliability score
        val (score, notes) = calcReliability(trades.size, daysRange)

        return QuantBacktestMetrics(
            totalReturn      = totalReturn,
            annualReturn     = annualReturn,
            mdd              = mdd,
            winRate          = winRate,
            profitFactor     = profitFactor,
            tradeCount       = trades.size,
            avgHoldingDays   = avgHoldingDays,
            benchmarkReturn  = benchmarkReturn,
            excessReturn     = excessReturn,
            reliabilityScore = score,
            reliabilityNotes = notes,
        )
    }

    private fun calcReliability(tradeCount: Int, daysRange: Int): Pair<String, Map<String, Any>> {
        val score = when {
            tradeCount >= 50 && daysRange >= 730 -> "A"
            tradeCount >= 20 && daysRange >= 365 -> "B"
            tradeCount >= 10                     -> "C"
            else                                 -> "D"
        }
        val notes = mapOf(
            "tradeCount" to tradeCount,
            "daysRange"  to daysRange,
            "reason"     to when (score) {
                "A" -> "50+ trades over 2+ years"
                "B" -> "20+ trades over 1+ year"
                "C" -> "10+ trades"
                else -> "insufficient data"
            },
        )
        return score to notes
    }

    private fun emptyResult(initialCapital: Double): QuantBacktestRunResult {
        val metrics = QuantBacktestMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, "D",
            mapOf("reason" to "no candle data"))
        return QuantBacktestRunResult(initialCapital, initialCapital, metrics, emptyList(), emptyList())
    }
}
