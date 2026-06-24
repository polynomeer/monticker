package com.monticker.api.paper.application

import kotlin.math.*

object RiskCalculator {

    private const val RISK_FREE_RATE = 0.03
    private const val TRADING_DAYS   = 252.0

    fun sharpe(dailyReturns: List<Double>): Double {
        if (dailyReturns.size < 5) return 0.0
        val avg = dailyReturns.average()
        val std = stdDev(dailyReturns)
        if (std == 0.0) return 0.0
        return (avg * TRADING_DAYS - RISK_FREE_RATE) / (std * sqrt(TRADING_DAYS))
    }

    fun beta(portfolioReturns: List<Double>, benchmarkReturns: List<Double>): Double {
        val n = minOf(portfolioReturns.size, benchmarkReturns.size)
        if (n < 5) return 1.0
        val p = portfolioReturns.takeLast(n)
        val b = benchmarkReturns.takeLast(n)
        val varB = variance(b)
        return if (varB == 0.0) 1.0 else covariance(p, b) / varB
    }

    fun maxDrawdown(equityCurve: List<Double>): Double {
        if (equityCurve.isEmpty()) return 0.0
        var peak = equityCurve[0]
        var mdd  = 0.0
        equityCurve.forEach { v ->
            peak = maxOf(peak, v)
            val dd = (peak - v) / peak * 100
            mdd = maxOf(mdd, dd)
        }
        return mdd
    }

    fun drawdownSeries(equityCurve: List<Double>): List<Double> {
        if (equityCurve.isEmpty()) return emptyList()
        var peak = equityCurve[0]
        return equityCurve.map { v ->
            peak = maxOf(peak, v)
            (peak - v) / peak * 100
        }
    }

    fun var95(dailyReturns: List<Double>): Double {
        if (dailyReturns.size < 10) return 0.0
        val sorted = dailyReturns.sorted()
        val idx = (dailyReturns.size * 0.05).toInt().coerceAtLeast(0)
        return -sorted[idx] * 100
    }

    fun annualizedVolatility(dailyReturns: List<Double>): Double {
        if (dailyReturns.size < 5) return 0.0
        return stdDev(dailyReturns) * sqrt(TRADING_DAYS) * 100
    }

    private fun stdDev(data: List<Double>): Double {
        if (data.isEmpty()) return 0.0
        val mean = data.average()
        return sqrt(data.map { (it - mean).pow(2) }.average())
    }

    private fun variance(data: List<Double>): Double {
        if (data.isEmpty()) return 0.0
        val mean = data.average()
        return data.map { (it - mean).pow(2) }.average()
    }

    private fun covariance(a: List<Double>, b: List<Double>): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        val meanA = a.average()
        val meanB = b.average()
        return a.zip(b).sumOf { (ai, bi) -> (ai - meanA) * (bi - meanB) } / n
    }
}
