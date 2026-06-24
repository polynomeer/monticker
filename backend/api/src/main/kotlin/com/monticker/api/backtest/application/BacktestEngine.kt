package com.monticker.api.backtest.application

import com.monticker.api.backtest.domain.*
import com.monticker.api.backtest.domain.strategies.*
import kotlin.math.*

object BacktestEngine {

    fun run(candles: List<DailyCandle>, request: BacktestRequest, symbol: String): BacktestResult {
        val strategy: BacktestStrategy = when (request.strategy) {
            StrategyType.MA_CROSSOVER  -> MaCrossoverStrategy(request.shortPeriod, request.longPeriod)
            StrategyType.RSI           -> RsiStrategy(request.rsiPeriod, request.rsiOversold, request.rsiOverbought)
            StrategyType.EMA_BREAKOUT  -> EmaBreakoutStrategy(request.emaPeriod, request.breakoutMultiplier)
        }

        val filtered = candles
            .filter { it.date >= request.fromDate && it.date <= request.toDate }
            .sortedBy { it.date }

        var cash     = request.initialCapital
        var holding  = 0
        var entryPrice = 0.0
        var entryDate  = filtered.first().date
        val trades   = mutableListOf<TradeRecord>()
        val equity   = mutableListOf<EquityPoint>()
        val strategyState = mutableMapOf<String, Any>()
        var peakEquity = request.initialCapital

        for ((idx, candle) in filtered.withIndex()) {
            val history = filtered.subList(0, idx + 1)
            val price   = candle.close.toDouble()
            val signal  = strategy.onCandle(candle, history, strategyState)

            // 보유 중 — 손절/익절 체크
            if (holding > 0) {
                val changePct = (price - entryPrice) / entryPrice * 100
                val exitReason = when {
                    changePct <= -request.stopLossPct   -> "STOP_LOSS"
                    changePct >= request.takeProfitPct  -> "TAKE_PROFIT"
                    signal == Signal.SELL               -> "SIGNAL"
                    else                                -> null
                }
                if (exitReason != null) {
                    val proceeds = holding * price
                    val pnl = proceeds - holding * entryPrice
                    trades.add(TradeRecord(
                        entryDate  = entryDate,
                        exitDate   = candle.date,
                        entryPrice = entryPrice,
                        exitPrice  = price,
                        quantity   = holding,
                        pnl        = pnl,
                        pnlPct     = changePct,
                        exitReason = exitReason,
                    ))
                    cash += proceeds
                    holding = 0
                    strategyState["holding"] = false
                }
            }

            // 미보유 — 매수 신호
            if (holding == 0 && signal == Signal.BUY && cash > price) {
                holding    = (cash * 0.95 / price).toInt().coerceAtLeast(1)
                entryPrice = price
                entryDate  = candle.date
                cash      -= holding * price
                strategyState["holding"] = true
            }

            // 당일 평가자산
            val totalEquity = cash + holding * price
            peakEquity = maxOf(peakEquity, totalEquity)
            val drawdown = (peakEquity - totalEquity) / peakEquity * 100
            equity.add(EquityPoint(candle.date, totalEquity, drawdown))
        }

        // 마지막 포지션 강제 청산
        if (holding > 0 && filtered.isNotEmpty()) {
            val last  = filtered.last()
            val price = last.close.toDouble()
            val pnl   = holding * (price - entryPrice)
            trades.add(TradeRecord(
                entryDate  = entryDate,
                exitDate   = last.date,
                entryPrice = entryPrice,
                exitPrice  = price,
                quantity   = holding,
                pnl        = pnl,
                pnlPct     = (price - entryPrice) / entryPrice * 100,
                exitReason = "END",
            ))
            cash += holding * price
        }

        val finalCapital = cash
        val metrics = calcMetrics(request.initialCapital, finalCapital, trades, equity, filtered.size)

        return BacktestResult(
            stockId       = request.stockId,
            symbol        = symbol,
            strategy      = request.strategy,
            fromDate      = request.fromDate,
            toDate        = request.toDate,
            initialCapital = request.initialCapital,
            finalCapital  = finalCapital,
            trades        = trades,
            equityCurve   = equity,
            metrics       = metrics,
        )
    }

    private fun calcMetrics(
        initial: Double, final: Double,
        trades: List<TradeRecord>, equity: List<EquityPoint>,
        totalDays: Int,
    ): BacktestMetrics {
        val totalReturn = (final - initial) / initial * 100
        val years = totalDays / 252.0
        val annualized = if (years > 0) ((final / initial).pow(1.0 / years) - 1) * 100 else totalReturn

        // Sharpe Ratio
        val dailyReturns = equity.zipWithNext { a, b -> (b.equity - a.equity) / a.equity }
        val avgReturn = dailyReturns.average()
        val stdReturn = sqrt(dailyReturns.map { (it - avgReturn).pow(2) }.average())
        val sharpe = if (stdReturn > 0) (avgReturn * 252 - 0.03) / (stdReturn * sqrt(252.0)) else 0.0

        val maxDd = equity.maxOfOrNull { it.drawdown } ?: 0.0
        val wins  = trades.count { it.pnl > 0 }
        val winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100 else 0.0
        val avgHolding = if (trades.isNotEmpty())
            trades.map { it.entryDate.until(it.exitDate, java.time.temporal.ChronoUnit.DAYS).toDouble() }.average()
        else 0.0
        val avgPnl = if (trades.isNotEmpty()) trades.map { it.pnlPct }.average() else 0.0
        val totalProfit = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
        val totalLoss   = trades.filter { it.pnl < 0 }.sumOf { -it.pnl }
        val profitFactor = if (totalLoss > 0) totalProfit / totalLoss else if (totalProfit > 0) 99.9 else 0.0

        return BacktestMetrics(
            totalReturn       = totalReturn,
            annualizedReturn  = annualized,
            sharpeRatio       = sharpe,
            maxDrawdown       = maxDd,
            winRate           = winRate,
            totalTrades       = trades.size,
            profitTrades      = wins,
            avgHoldingDays    = avgHolding,
            avgPnlPct         = avgPnl,
            profitFactor      = profitFactor,
        )
    }
}
