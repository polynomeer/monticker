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
    // ADR-079 — 거래 비용. 기본은 비용 없음(이전 동작 그대로)
    val costs: CostModel = CostModel.NONE,
)

/**
 * 단순 백테스트 거래 비용(ADR-079). 모두 % 단위, 0이면 미적용.
 * - commissionPct: 매수·매도 양쪽 체결금액에
 * - sellTaxPct: 매도 체결금액에. 국내 시장(KOSPI·KOSDAQ) 종목에만 적용한다 — 세율은 시기·시장별로
 *   달라 서버가 단정하지 않고 요청이 정한 가정값을 쓴다.
 * - slippagePct: 매수는 종가보다 비싸게, 매도는 싸게 체결된 것으로 본다
 */
data class CostModel(
    val commissionPct: Double = 0.0,
    val sellTaxPct: Double = 0.0,
    val slippagePct: Double = 0.0,
) {
    init {
        require(commissionPct in 0.0..1.0) { "수수료는 0~1% 사이여야 합니다." }
        require(sellTaxPct in 0.0..1.0) { "세율은 0~1% 사이여야 합니다." }
        require(slippagePct in 0.0..2.0) { "슬리피지는 0~2% 사이여야 합니다." }
    }

    companion object {
        val NONE = CostModel()
    }
}

/** 응답에 실제로 적용한 비용과 누적 비용을 돌려준다 — 화면이 "반영됨"을 서버 기준으로 보여 주도록. */
data class AppliedCosts(
    val commissionPct: Double,
    val sellTaxPct: Double,
    val slippagePct: Double,
    /** 해외 종목이면 false — sellTaxPct를 요청했어도 적용하지 않았다 */
    val sellTaxApplied: Boolean,
    /** 수수료 + 세금 + 슬리피지로 잃은 금액(원) */
    val totalCost: Double,
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
    val costs: AppliedCosts,
)
