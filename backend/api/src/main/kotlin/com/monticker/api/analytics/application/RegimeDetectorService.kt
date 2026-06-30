package com.monticker.api.analytics.application

import com.monticker.api.analytics.domain.RegimeHistory
import com.monticker.api.analytics.infrastructure.RegimeHistoryRepository
import com.monticker.api.backtest.domain.DailyCandle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

data class RegimeResult(
    val regime: String,
    val adx: Double,
    val volatility: Double,
    val trendSlope: Double,
    val explanation: String,
    val error: String? = null,
)

@Service
class RegimeDetectorService(
    private val jdbc: JdbcTemplate,
    private val regimeHistoryRepository: RegimeHistoryRepository,
) {
    @Transactional
    fun classifyRegime(stockId: Long): RegimeResult {
        val to = LocalDate.now()
        val from = to.minusDays(400)
        val candles = loadDailyCandles(stockId, from, to)

        if (candles.size < 30) {
            return RegimeResult(
                regime = "UNKNOWN", adx = 0.0, volatility = 0.0, trendSlope = 0.0,
                explanation = "데이터 부족", error = "데이터 부족: 최소 30일 데이터가 필요합니다",
            )
        }

        val result = computeRegime(candles)

        val today = candles.last().date
        val existing = regimeHistoryRepository.findByStockIdAndRegimeDate(stockId, today)
        if (existing == null) {
            regimeHistoryRepository.save(
                RegimeHistory(
                    stockId = stockId,
                    market = null,
                    regimeDate = today,
                    regime = result.regime,
                    adx = BigDecimal.valueOf(result.adx),
                    volatility = BigDecimal.valueOf(result.volatility),
                    trendSlope = BigDecimal.valueOf(result.trendSlope),
                )
            )
        }

        return result
    }

    @Transactional
    fun classifyMarketRegime(market: String): RegimeResult {
        // Use a representative stock for the market (first stock matching market code) as a proxy.
        val representativeStockId = jdbc.queryForList(
            "SELECT id FROM stocks WHERE market = ? ORDER BY id LIMIT 1",
            Long::class.java, market
        ).firstOrNull()
            ?: return RegimeResult(
                regime = "UNKNOWN", adx = 0.0, volatility = 0.0, trendSlope = 0.0,
                explanation = "대표 종목을 찾을 수 없습니다", error = "시장 데이터 부족",
            )

        val to = LocalDate.now()
        val from = to.minusDays(400)
        val candles = loadDailyCandles(representativeStockId, from, to)

        if (candles.size < 30) {
            return RegimeResult(
                regime = "UNKNOWN", adx = 0.0, volatility = 0.0, trendSlope = 0.0,
                explanation = "데이터 부족", error = "데이터 부족: 최소 30일 데이터가 필요합니다",
            )
        }

        val result = computeRegime(candles)
        val today = candles.last().date
        val existing = regimeHistoryRepository.findByMarketAndRegimeDate(market, today)
        if (existing == null) {
            regimeHistoryRepository.save(
                RegimeHistory(
                    stockId = null,
                    market = market,
                    regimeDate = today,
                    regime = result.regime,
                    adx = BigDecimal.valueOf(result.adx),
                    volatility = BigDecimal.valueOf(result.volatility),
                    trendSlope = BigDecimal.valueOf(result.trendSlope),
                )
            )
        }

        return result
    }

    private fun computeRegime(candles: List<DailyCandle>): RegimeResult {
        val adx = calculateADX(candles)
        val volatility = calculateVolatility(candles)
        val slope = calculateTrendSlope(candles)

        // Use a fixed 80th-percentile-style volatility threshold approximation: 1.5x the
        // trailing-window average volatility computed across rolling windows, falling back to
        // an absolute 40% annualized vol threshold when there isn't enough history for windows.
        val volSeries = rollingVolatilitySeries(candles)
        val volatilityPercentile80 = if (volSeries.size >= 5) {
            volSeries.sorted()[(volSeries.size * 0.8).toInt().coerceAtMost(volSeries.size - 1)]
        } else {
            0.40
        }

        val regime = classify(adx, volatility, volatilityPercentile80, slope)
        val explanation = when (regime) {
            "HIGH_VOL" -> "변동성이 평소보다 높은 고변동성 국면입니다 (변동성 ${"%.1f".format(volatility * 100)}%)."
            "SIDEWAYS" -> "추세 강도(ADX ${"%.1f".format(adx)})가 낮아 횡보 국면으로 판단됩니다."
            "BULL" -> "ADX ${"%.1f".format(adx)}, 상승 기울기로 강세장(BULL) 국면입니다."
            else -> "ADX ${"%.1f".format(adx)}, 하락 기울기로 약세장(BEAR) 국면입니다."
        }

        return RegimeResult(regime, adx, volatility, slope, explanation)
    }

    // ─── ADX ───────────────────────────────────────────────────────────────────

    fun calculateADX(candles: List<DailyCandle>, period: Int = 14): Double {
        if (candles.size < period * 2) return 0.0

        val plusDM = DoubleArray(candles.size)
        val minusDM = DoubleArray(candles.size)
        val tr = DoubleArray(candles.size)

        for (i in 1 until candles.size) {
            val upMove = candles[i].high.toDouble() - candles[i - 1].high.toDouble()
            val downMove = candles[i - 1].low.toDouble() - candles[i].low.toDouble()
            plusDM[i] = if (upMove > downMove && upMove > 0) upMove else 0.0
            minusDM[i] = if (downMove > upMove && downMove > 0) downMove else 0.0

            val high = candles[i].high.toDouble()
            val low = candles[i].low.toDouble()
            val prevClose = candles[i - 1].close.toDouble()
            tr[i] = maxOf(high - low, abs(high - prevClose), abs(low - prevClose))
        }

        // Wilder smoothing
        var smoothedTR = tr.drop(1).take(period).sum()
        var smoothedPlusDM = plusDM.drop(1).take(period).sum()
        var smoothedMinusDM = minusDM.drop(1).take(period).sum()

        val dxValues = mutableListOf<Double>()

        for (i in (period + 1) until candles.size) {
            smoothedTR = smoothedTR - (smoothedTR / period) + tr[i]
            smoothedPlusDM = smoothedPlusDM - (smoothedPlusDM / period) + plusDM[i]
            smoothedMinusDM = smoothedMinusDM - (smoothedMinusDM / period) + minusDM[i]

            val plusDI = if (smoothedTR != 0.0) smoothedPlusDM / smoothedTR * 100 else 0.0
            val minusDI = if (smoothedTR != 0.0) smoothedMinusDM / smoothedTR * 100 else 0.0
            val sum = plusDI + minusDI
            val dx = if (sum != 0.0) abs(plusDI - minusDI) / sum * 100 else 0.0
            dxValues.add(dx)
        }

        if (dxValues.isEmpty()) return 0.0
        return dxValues.takeLast(period).average()
    }

    // ─── Volatility ────────────────────────────────────────────────────────────

    fun calculateVolatility(candles: List<DailyCandle>, period: Int = 20): Double {
        val window = candles.takeLast(period + 1)
        if (window.size < 2) return 0.0
        val returns = window.zipWithNext { a, b ->
            val prev = a.close.toDouble()
            val curr = b.close.toDouble()
            if (prev == 0.0) 0.0 else (curr - prev) / prev
        }
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size.coerceAtLeast(1)
        return sqrt(variance) * sqrt(252.0)
    }

    private fun rollingVolatilitySeries(candles: List<DailyCandle>, windowPeriod: Int = 20): List<Double> {
        if (candles.size < windowPeriod + 2) return emptyList()
        val series = mutableListOf<Double>()
        for (end in windowPeriod until candles.size) {
            val window = candles.subList(end - windowPeriod, end + 1)
            series.add(calculateVolatility(window, windowPeriod))
        }
        return series
    }

    // ─── Trend slope ───────────────────────────────────────────────────────────

    fun calculateTrendSlope(candles: List<DailyCandle>, period: Int = 60): Double {
        val window = candles.takeLast(period)
        if (window.size < 2) return 0.0
        val closes = window.map { it.close.toDouble() }
        val n = closes.size
        val xs = (0 until n).map { it.toDouble() }
        val xMean = xs.average()
        val yMean = closes.average()
        val numerator = xs.indices.sumOf { (xs[it] - xMean) * (closes[it] - yMean) }
        val denominator = xs.sumOf { (it - xMean) * (it - xMean) }
        val slope = if (denominator != 0.0) numerator / denominator else 0.0
        return if (yMean != 0.0) slope / yMean else 0.0
    }

    fun classify(adx: Double, volatility: Double, volatilityPercentile80: Double, slope: Double): String {
        return when {
            volatility > volatilityPercentile80 -> "HIGH_VOL"
            adx < 20 -> "SIDEWAYS"
            slope > 0 -> "BULL"
            else -> "BEAR"
        }
    }

    // ─── Data loading ──────────────────────────────────────────────────────────

    private fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> {
        return jdbc.query(
            """
            SELECT
                DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
                (ARRAY_AGG(open  ORDER BY candle_time ASC))[1]  AS open,
                MAX(high)                                        AS high,
                MIN(low)                                         AS low,
                (ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close,
                SUM(volume)                                      AS volume
            FROM candles_1m
            WHERE stock_id = ?
              AND DATE(candle_time AT TIME ZONE 'Asia/Seoul') BETWEEN ? AND ?
            GROUP BY d
            ORDER BY d
            """.trimIndent(),
            { rs, _ ->
                DailyCandle(
                    date = rs.getDate("d").toLocalDate(),
                    open = rs.getBigDecimal("open"),
                    high = rs.getBigDecimal("high"),
                    low = rs.getBigDecimal("low"),
                    close = rs.getBigDecimal("close"),
                    volume = rs.getLong("volume"),
                )
            },
            stockId, from, to,
        )
    }
}
