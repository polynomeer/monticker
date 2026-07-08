package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.RuleCondition
import com.monticker.api.quant.domain.RuleGroup

object RuleEvaluator {

    fun evaluateEntry(
        group: RuleGroup,
        candles: List<DailyCandle>,
        idx: Int,
    ): Boolean {
        val results = group.conditions.map { evaluateEntryCondition(it, candles, idx) }
        return combine(group.operator, results)
    }

    fun evaluateExit(
        group: RuleGroup,
        candles: List<DailyCandle>,
        idx: Int,
        entryPrice: Double,
        currentPrice: Double,
    ): Boolean {
        val results = group.conditions.map { evaluateExitCondition(it, candles, idx, entryPrice, currentPrice) }
        return combine(group.operator, results)
    }

    private fun combine(operator: String, results: List<Boolean>): Boolean =
        when (operator.uppercase()) {
            "AND" -> results.all { it }
            "OR"  -> results.any { it }
            else  -> results.all { it }
        }

    private fun evaluateEntryCondition(
        cond: RuleCondition,
        candles: List<DailyCandle>,
        idx: Int,
    ): Boolean {
        val period = (cond.params["period"] as? Number)?.toInt() ?: 20
        val candle = candles[idx]
        val close  = candle.close.toDouble()

        return when (cond.indicator.uppercase()) {
            "CLOSE_VS_MA" -> {
                val maVal = IndicatorEngine.ma(candles, period, idx) ?: return false
                compare(cond.comparator, close, cond.value, maVal)
            }
            "VOLUME_RATIO" -> {
                val avgVol = IndicatorEngine.avgVolume(candles, period, idx) ?: return false
                if (avgVol == 0.0) return false
                val ratio = candle.volume.toDouble() / avgVol
                compare(cond.comparator, ratio, cond.value, null)
            }
            "RSI" -> {
                val rsiVal = IndicatorEngine.rsi(candles, period, idx) ?: return false
                compare(cond.comparator, rsiVal, cond.value, null)
            }
            "MACD_CROSS" -> {
                if (idx < 1) return false
                val curr = IndicatorEngine.macd(candles, idx) ?: return false
                val prev = IndicatorEngine.macd(candles, idx - 1) ?: return false
                when (cond.comparator.uppercase()) {
                    "GOLDEN" -> prev.macdLine <= prev.signalLine && curr.macdLine > curr.signalLine
                    "DEAD"   -> prev.macdLine >= prev.signalLine && curr.macdLine < curr.signalLine
                    else     -> false
                }
            }
            "PRICE_CHANGE" -> {
                if (idx < period) return false
                val prevClose = candles[idx - period].close.toDouble()
                if (prevClose == 0.0) return false
                val changePct = (close - prevClose) / prevClose * 100
                compare(cond.comparator, changePct, cond.value, null)
            }
            "BOLLINGER_BAND" -> {
                val bb = IndicatorEngine.bollingerBands(candles, period, idx) ?: return false
                when (cond.comparator.uppercase()) {
                    "ABOVE_UPPER" -> close > bb.upper
                    "BELOW_LOWER" -> close < bb.lower
                    else          -> false
                }
            }
            else -> false
        }
    }

    private fun evaluateExitCondition(
        cond: RuleCondition,
        candles: List<DailyCandle>,
        idx: Int,
        entryPrice: Double,
        currentPrice: Double,
    ): Boolean {
        if (entryPrice == 0.0) return false
        val returnPct = (currentPrice - entryPrice) / entryPrice * 100

        return when (cond.indicator.uppercase()) {
            "PROFIT_RATE" -> compare(cond.comparator, returnPct, cond.value, null)
            "LOSS_RATE"   -> compare(cond.comparator, returnPct, cond.value, null)
            else          -> evaluateEntryCondition(cond, candles, idx)
        }
    }

    /**
     * Compare `actual` against `condValue` (or fallback `impliedValue` for indicators like CLOSE_VS_MA).
     * condValue may be:
     *   - null          → boolean indicator (e.g. CLOSE_VS_MA without explicit value → compare actual > impliedValue)
     *   - Number        → single threshold
     *   - List<Number>  → [lo, hi] for BETWEEN
     */
    private fun compare(comparator: String, actual: Double, condValue: Any?, impliedValue: Double?): Boolean {
        return when (comparator.uppercase()) {
            "GT"      -> actual > (toDouble(condValue) ?: impliedValue ?: return false)
            "GTE"     -> actual >= (toDouble(condValue) ?: impliedValue ?: return false)
            "LT"      -> actual < (toDouble(condValue) ?: impliedValue ?: return false)
            "LTE"     -> actual <= (toDouble(condValue) ?: impliedValue ?: return false)
            "EQ"      -> actual == (toDouble(condValue) ?: impliedValue ?: return false)
            "BETWEEN" -> {
                val list = condValue as? List<*> ?: return false
                val lo   = (list[0] as? Number)?.toDouble() ?: return false
                val hi   = (list[1] as? Number)?.toDouble() ?: return false
                actual in lo..hi
            }
            else -> false
        }
    }

    private fun toDouble(v: Any?): Double? = (v as? Number)?.toDouble()
}
