package com.monticker.api.quant.application

import com.monticker.api.quant.domain.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

object QuantBacktestEngine {

    private const val COMMISSION_RATE = 0.00015   // 0.015%
    private const val SLIPPAGE_RATE   = 0.001     // 0.1%

    fun run(
        candles: List<DailyCandle>,
        ruleDef: RuleDefinition,
        initialCapital: Double,
        fromDate: java.time.LocalDate,
        toDate: java.time.LocalDate,
    ): QuantBacktestRunResult {
        val filtered = candles
            .filter { it.date >= fromDate && it.date <= toDate }
            .sortedBy { it.date }

        if (filtered.isEmpty()) {
            return emptyResult(initialCapital)
        }

        var cash        = initialCapital
        var holding     = 0
        var entryPrice  = 0.0
        var entryDate   = filtered.first().date
        val trades      = mutableListOf<QuantTradeRecord>()
        val equity      = mutableListOf<QuantEquityPoint>()
        var peakEquity  = initialCapital

        for ((idx, candle) in filtered.withIndex()) {
            val price = candle.close.toDouble()

            // Exit evaluation
            if (holding > 0) {
                val shouldExit = RuleEvaluator.evaluateExit(ruleDef.exitRules, filtered, idx, entryPrice, price)
                if (shouldExit) {
                    val exitPrice = price * (1 - SLIPPAGE_RATE)
                    val commission = holding * exitPrice * COMMISSION_RATE
                    val proceeds = holding * exitPrice - commission
                    val pnl = proceeds - holding * entryPrice
                    val pnlPct = (exitPrice - entryPrice) / entryPrice * 100
                    trades.add(QuantTradeRecord(
                        entryDate  = entryDate,
                        exitDate   = candle.date,
                        entryPrice = entryPrice,
                        exitPrice  = exitPrice,
                        quantity   = holding,
                        pnl        = pnl,
                        pnlPct     = pnlPct,
                        exitReason = "SIGNAL",
                    ))
                    cash   += proceeds
                    holding = 0
                }
            }

            // Entry evaluation
            if (holding == 0) {
                val shouldEnter = RuleEvaluator.evaluateEntry(ruleDef.entryRules, filtered, idx, )
                if (shouldEnter && cash > price) {
                    val ratio      = ruleDef.positionSizing.value / 100.0
                    val buyPrice   = price * (1 + SLIPPAGE_RATE)
                    val budget     = cash * ratio
                    val qty        = (budget / buyPrice).toInt().coerceAtLeast(1)
                    val commission = qty * buyPrice * COMMISSION_RATE
                    val cost       = qty * buyPrice + commission
                    if (cost <= cash) {
                        holding    = qty
                        entryPrice = buyPrice
                        entryDate  = candle.date
                        cash      -= cost
                    }
                }
            }

            val totalEquity = cash + holding * price
            peakEquity = max(peakEquity, totalEquity)
            val drawdown = if (peakEquity > 0) (peakEquity - totalEquity) / peakEquity * 100 else 0.0
            equity.add(QuantEquityPoint(candle.date, totalEquity, drawdown))
        }

        // Force-close last position
        if (holding > 0 && filtered.isNotEmpty()) {
            val last       = filtered.last()
            val exitPrice  = last.close.toDouble() * (1 - SLIPPAGE_RATE)
            val commission = holding * exitPrice * COMMISSION_RATE
            val proceeds   = holding * exitPrice - commission
            val pnl        = proceeds - holding * entryPrice
            trades.add(QuantTradeRecord(
                entryDate  = entryDate,
                exitDate   = last.date,
                entryPrice = entryPrice,
                exitPrice  = exitPrice,
                quantity   = holding,
                pnl        = pnl,
                pnlPct     = (exitPrice - entryPrice) / entryPrice * 100,
                exitReason = "END",
            ))
            cash += proceeds
        }

        val finalCapital = cash
        val metrics = calcMetrics(initialCapital, finalCapital, trades, equity, filtered)
        return QuantBacktestRunResult(initialCapital, finalCapital, metrics, trades, equity)
    }

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
