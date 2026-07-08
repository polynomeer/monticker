package com.monticker.api.paper.application

data class RiskMetricsResponse(
    val sharpeRatio: Double,
    val beta: Double,
    val maxDrawdown: Double,
    val volatility: Double,
    val var95: Double,
    val winRate: Double,
    val totalTrades: Int,
    val avgReturn: Double,
    val dailyReturns: List<DailyReturn>,
    val drawdownSeries: List<DrawdownPoint>,
    val hasEnoughData: Boolean,
    val message: String?,
)

data class DailyReturn(val date: String, val returnPct: Double)
data class DrawdownPoint(val date: String, val drawdown: Double)
