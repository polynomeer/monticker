package com.monticker.api.analytics.application

import com.monticker.api.backtest.domain.DailyCandle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.abs

@Service
@Transactional(readOnly = true)
class PatternRecognizerQueryService(
    private val jdbc: JdbcTemplate,
) {
    fun detectPatternsCompute(stockId: Long, lookbackDays: Int = 90): List<PatternMatch> {
        val to = LocalDate.now()
        val from = to.minusDays((lookbackDays * 2).toLong().coerceAtLeast(30))
        val candles = loadDailyCandles(stockId, from, to).takeLast(lookbackDays)

        if (candles.size < 30) return emptyList()

        val swings = zigZag(candles)
        if (swings.size < 3) return emptyList()

        return listOfNotNull(
            detectDoubleBottom(swings),
            detectDoubleTop(swings),
            detectHeadAndShoulders(swings),
            detectAscendingTriangle(swings),
            detectDescendingTriangle(swings),
        ).filter { it.confidenceScore >= 60 }
    }

    fun zigZag(candles: List<DailyCandle>, thresholdPct: Double = 3.0): List<SwingPoint> {
        if (candles.isEmpty()) return emptyList()

        val swings = mutableListOf<SwingPoint>()
        var direction: SwingType? = null
        var extremeIndex = 0
        var extremePrice = candles[0].close.toDouble()

        for (i in 1 until candles.size) {
            val price = candles[i].close.toDouble()
            when (direction) {
                null -> {
                    if (price > extremePrice) {
                        extremePrice = price; extremeIndex = i; direction = SwingType.HIGH
                    } else if (price < extremePrice) {
                        extremePrice = price; extremeIndex = i; direction = SwingType.LOW
                    }
                }
                SwingType.HIGH -> {
                    if (price > extremePrice) {
                        extremePrice = price; extremeIndex = i
                    } else if ((extremePrice - price) / extremePrice * 100 >= thresholdPct) {
                        swings.add(SwingPoint(extremeIndex, candles[extremeIndex].date, candles[extremeIndex].close, SwingType.HIGH))
                        extremePrice = price; extremeIndex = i; direction = SwingType.LOW
                    }
                }
                SwingType.LOW -> {
                    if (price < extremePrice) {
                        extremePrice = price; extremeIndex = i
                    } else if ((price - extremePrice) / extremePrice * 100 >= thresholdPct) {
                        swings.add(SwingPoint(extremeIndex, candles[extremeIndex].date, candles[extremeIndex].close, SwingType.LOW))
                        extremePrice = price; extremeIndex = i; direction = SwingType.HIGH
                    }
                }
            }
        }
        if (direction != null) {
            swings.add(SwingPoint(extremeIndex, candles[extremeIndex].date, candles[extremeIndex].close, direction))
        }
        return swings
    }

    fun detectDoubleBottom(swings: List<SwingPoint>): PatternMatch? {
        if (swings.size < 3) return null
        val last3 = swings.takeLast(3)
        if (last3[0].type != SwingType.LOW || last3[1].type != SwingType.HIGH || last3[2].type != SwingType.LOW) return null

        val low1 = last3[0].price.toDouble()
        val high = last3[1].price.toDouble()
        val low2 = last3[2].price.toDouble()
        val diffPct = abs(low1 - low2) / low1 * 100
        if (diffPct > 2.0) return null
        if ((high - maxOf(low1, low2)) / maxOf(low1, low2) * 100 < 5.0) return null

        return PatternMatch(
            patternType = "DOUBLE_BOTTOM",
            confidenceScore = (100 - diffPct * 10).coerceIn(0.0, 100.0).toInt(),
            swingPoints = last3,
            candleFrom = last3.first().date,
            candleTo = last3.last().date,
            description = "통계적으로 상승 반전 가능성이 있는 더블 바텀 패턴입니다",
        )
    }

    fun detectDoubleTop(swings: List<SwingPoint>): PatternMatch? {
        if (swings.size < 3) return null
        val last3 = swings.takeLast(3)
        if (last3[0].type != SwingType.HIGH || last3[1].type != SwingType.LOW || last3[2].type != SwingType.HIGH) return null

        val high1 = last3[0].price.toDouble()
        val low = last3[1].price.toDouble()
        val high2 = last3[2].price.toDouble()
        val diffPct = abs(high1 - high2) / high1 * 100
        if (diffPct > 2.0) return null
        if ((minOf(high1, high2) - low) / minOf(high1, high2) * 100 < 5.0) return null

        return PatternMatch(
            patternType = "DOUBLE_TOP",
            confidenceScore = (100 - diffPct * 10).coerceIn(0.0, 100.0).toInt(),
            swingPoints = last3,
            candleFrom = last3.first().date,
            candleTo = last3.last().date,
            description = "통계적으로 하락 반전 가능성이 있는 더블 탑 패턴입니다",
        )
    }

    fun detectHeadAndShoulders(swings: List<SwingPoint>): PatternMatch? {
        if (swings.size < 5) return null
        val last5 = swings.takeLast(5)
        if (last5.map { it.type } != listOf(SwingType.HIGH, SwingType.LOW, SwingType.HIGH, SwingType.LOW, SwingType.HIGH)) return null

        val shoulder1 = last5[0].price.toDouble()
        val head = last5[2].price.toDouble()
        val shoulder2 = last5[4].price.toDouble()
        if (head <= shoulder1 || head <= shoulder2) return null
        val shoulderDiffPct = abs(shoulder1 - shoulder2) / shoulder1 * 100
        if (shoulderDiffPct > 3.0) return null

        return PatternMatch(
            patternType = "HEAD_AND_SHOULDERS",
            confidenceScore = (100 - shoulderDiffPct * 10).coerceIn(0.0, 100.0).toInt(),
            swingPoints = last5,
            candleFrom = last5.first().date,
            candleTo = last5.last().date,
            description = "통계적으로 하락 반전 가능성이 있는 헤드 앤 숄더 패턴입니다",
        )
    }

    fun detectAscendingTriangle(swings: List<SwingPoint>): PatternMatch? {
        if (swings.size < 4) return null
        val last4 = swings.takeLast(4)
        val highs = last4.filter { it.type == SwingType.HIGH }
        val lows = last4.filter { it.type == SwingType.LOW }
        if (highs.size < 2 || lows.size < 2) return null

        val highValues = highs.map { it.price.toDouble() }
        val highDiffPct = (highValues.max() - highValues.min()) / highValues.average() * 100
        if (highDiffPct > 2.0) return null
        if (!lows.map { it.price.toDouble() }.zipWithNext().all { (a, b) -> b > a }) return null

        return PatternMatch(
            patternType = "ASCENDING_TRIANGLE",
            confidenceScore = (100 - highDiffPct * 10).coerceIn(0.0, 100.0).toInt(),
            swingPoints = last4,
            candleFrom = last4.first().date,
            candleTo = last4.last().date,
            description = "저항선은 횡보하고 지지선은 상승하는 상승 삼각형 패턴입니다",
        )
    }

    fun detectDescendingTriangle(swings: List<SwingPoint>): PatternMatch? {
        if (swings.size < 4) return null
        val last4 = swings.takeLast(4)
        val highs = last4.filter { it.type == SwingType.HIGH }
        val lows = last4.filter { it.type == SwingType.LOW }
        if (highs.size < 2 || lows.size < 2) return null

        val lowValues = lows.map { it.price.toDouble() }
        val lowDiffPct = (lowValues.max() - lowValues.min()) / lowValues.average() * 100
        if (lowDiffPct > 2.0) return null
        if (!highs.map { it.price.toDouble() }.zipWithNext().all { (a, b) -> b < a }) return null

        return PatternMatch(
            patternType = "DESCENDING_TRIANGLE",
            confidenceScore = (100 - lowDiffPct * 10).coerceIn(0.0, 100.0).toInt(),
            swingPoints = last4,
            candleFrom = last4.first().date,
            candleTo = last4.last().date,
            description = "지지선은 횡보하고 저항선은 하락하는 하락 삼각형 패턴입니다",
        )
    }

    private fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> =
        jdbc.query(
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
