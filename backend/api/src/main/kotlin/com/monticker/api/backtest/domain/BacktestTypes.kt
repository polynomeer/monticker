package com.monticker.api.backtest.domain

import java.math.BigDecimal
import java.time.LocalDate

enum class Signal { BUY, SELL, HOLD }
enum class StrategyType { MA_CROSSOVER, RSI, EMA_BREAKOUT }

data class DailyCandle(
    val date: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class BacktestRequest(
    val stockId: Long,
    val strategy: StrategyType,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val initialCapital: Double = 10_000_000.0,
    // MA Crossover params
    val shortPeriod: Int = 5,
    val longPeriod: Int = 20,
    // RSI params
    val rsiPeriod: Int = 14,
    val rsiOversold: Double = 30.0,
    val rsiOverbought: Double = 70.0,
    // EMA Breakout params
    val emaPeriod: Int = 20,
    val breakoutMultiplier: Double = 1.5,
    // Stop loss / take profit
    val stopLossPct: Double = 5.0,   // 5% 손절
    val takeProfitPct: Double = 10.0, // 10% 익절
)

data class TradeRecord(
    val entryDate: LocalDate,
    val exitDate: LocalDate,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Int,
    val pnl: Double,
    val pnlPct: Double,
    val exitReason: String,   // "SIGNAL" | "STOP_LOSS" | "TAKE_PROFIT" | "END"
)

data class EquityPoint(
    val date: LocalDate,
    val equity: Double,
    val drawdown: Double,   // 고점 대비 %
)

data class BacktestMetrics(
    val totalReturn: Double,        // 총 수익률 %
    val annualizedReturn: Double,   // 연환산 수익률 %
    val sharpeRatio: Double,        // Sharpe Ratio (무위험수익률 3% 가정)
    val maxDrawdown: Double,        // 최대 낙폭 %
    val winRate: Double,            // 승률 %
    val totalTrades: Int,
    val profitTrades: Int,
    val avgHoldingDays: Double,
    val avgPnlPct: Double,
    val profitFactor: Double,       // 총수익 / 총손실
)

data class BacktestResult(
    val stockId: Long,
    val symbol: String,
    val strategy: StrategyType,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val initialCapital: Double,
    val finalCapital: Double,
    val trades: List<TradeRecord>,
    val equityCurve: List<EquityPoint>,
    val metrics: BacktestMetrics,
)
