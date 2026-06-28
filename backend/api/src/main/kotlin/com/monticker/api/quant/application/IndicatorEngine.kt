package com.monticker.api.quant.application

import com.monticker.api.quant.domain.BollingerBands
import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.MacdValue
import kotlin.math.sqrt

object IndicatorEngine {

    /**
     * Simple Moving Average up to and including upToIndex.
     */
    fun ma(candles: List<DailyCandle>, period: Int, upToIndex: Int): Double? {
        if (upToIndex < period - 1) return null
        val slice = candles.subList(upToIndex - period + 1, upToIndex + 1)
        return slice.map { it.close.toDouble() }.average()
    }

    /**
     * Exponential Moving Average — returns EMA at upToIndex.
     * Uses all candles from index 0 to upToIndex.
     */
    fun ema(candles: List<DailyCandle>, period: Int, upToIndex: Int): Double? {
        if (upToIndex < period - 1) return null
        val k = 2.0 / (period + 1)
        var ema = candles.subList(0, period).map { it.close.toDouble() }.average()
        for (i in period..upToIndex) {
            ema = candles[i].close.toDouble() * k + ema * (1 - k)
        }
        return ema
    }

    /**
     * RSI(period) at upToIndex using Wilder smoothing.
     */
    fun rsi(candles: List<DailyCandle>, period: Int, upToIndex: Int): Double? {
        if (upToIndex < period) return null
        val closes = candles.subList(0, upToIndex + 1).map { it.close.toDouble() }
        val changes = closes.zipWithNext { a, b -> b - a }
        // Initial average gain/loss over first `period` changes
        var avgGain = changes.subList(0, period).filter { it > 0 }.sum() / period
        var avgLoss = changes.subList(0, period).filter { it < 0 }.map { -it }.sum() / period

        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1 + rs)
    }

    /**
     * MACD(12, 26, 9) at upToIndex.
     */
    fun macd(candles: List<DailyCandle>, upToIndex: Int): MacdValue? {
        if (upToIndex < 33) return null  // need at least 26 + 9 - 1 candles
        val fast = ema(candles, 12, upToIndex) ?: return null
        val slow = ema(candles, 26, upToIndex) ?: return null
        val macdLine = fast - slow

        // Build MACD line series for signal calculation
        val macdSeries = mutableListOf<Double>()
        val startIdx = 25  // 26-1
        for (i in startIdx..upToIndex) {
            val f = ema(candles, 12, i) ?: continue
            val s = ema(candles, 26, i) ?: continue
            macdSeries.add(f - s)
        }

        // EMA(9) of macdSeries
        if (macdSeries.size < 9) return null
        val signalK = 2.0 / (9 + 1)
        var signal = macdSeries.subList(0, 9).average()
        for (i in 9 until macdSeries.size) {
            signal = macdSeries[i] * signalK + signal * (1 - signalK)
        }

        return MacdValue(macdLine, signal, macdLine - signal)
    }

    /**
     * Bollinger Bands(period, 2σ) at upToIndex.
     */
    fun bollingerBands(candles: List<DailyCandle>, period: Int, upToIndex: Int): BollingerBands? {
        if (upToIndex < period - 1) return null
        val slice = candles.subList(upToIndex - period + 1, upToIndex + 1).map { it.close.toDouble() }
        val mean = slice.average()
        val variance = slice.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        return BollingerBands(mean + 2 * stdDev, mean, mean - 2 * stdDev)
    }

    /**
     * Average volume over period candles ending at upToIndex.
     */
    fun avgVolume(candles: List<DailyCandle>, period: Int, upToIndex: Int): Double? {
        if (upToIndex < period - 1) return null
        val slice = candles.subList(upToIndex - period + 1, upToIndex + 1)
        return slice.map { it.volume.toDouble() }.average()
    }
}
