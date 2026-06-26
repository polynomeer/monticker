package com.monticker.api.marketdata.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class VwapService(private val jdbc: JdbcTemplate) {

    /**
     * VWAP = Σ(가격 × 거래량) / Σ(거래량)
     * 당일 candles_1m 기준으로 계산
     */
    fun getDailyVwap(stockId: Long): VwapResponse {
        val startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS)

        val result = jdbc.queryForMap(
            """
            SELECT
                SUM(close * volume)  AS price_volume,
                SUM(volume)          AS total_volume,
                COUNT(*)             AS candle_count
            FROM candles_1m
            WHERE stock_id = ?
              AND candle_time >= ?
            """,
            stockId,
            java.sql.Timestamp.from(startOfDay),
        )

        val priceVolume = (result["price_volume"] as? Number)?.toDouble() ?: 0.0
        val totalVolume = (result["total_volume"] as? Number)?.toLong()  ?: 0L
        val candleCount = (result["candle_count"] as? Number)?.toInt()   ?: 0

        val vwap = if (totalVolume > 0)
            BigDecimal(priceVolume / totalVolume).setScale(4, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        return VwapResponse(
            stockId     = stockId,
            vwap        = vwap,
            totalVolume = totalVolume,
            candleCount = candleCount,
            since       = startOfDay,
        )
    }

    /** 1분봉 기준 누적 VWAP 시계열 (차트 오버레이용) */
    fun getVwapSeries(stockId: Long): List<VwapPoint> {
        val startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS)

        return jdbc.query(
            """
            SELECT
                candle_time,
                SUM(close * volume) OVER (ORDER BY candle_time) AS cum_price_vol,
                SUM(volume)         OVER (ORDER BY candle_time) AS cum_volume
            FROM candles_1m
            WHERE stock_id = ?
              AND candle_time >= ?
            ORDER BY candle_time
            """,
            { rs, _ ->
                val cumPriceVol = rs.getDouble("cum_price_vol")
                val cumVolume   = rs.getLong("cum_volume")
                val vwap = if (cumVolume > 0)
                    BigDecimal(cumPriceVol / cumVolume).setScale(4, RoundingMode.HALF_UP)
                else BigDecimal.ZERO
                VwapPoint(
                    time = rs.getTimestamp("candle_time").toInstant().epochSecond,
                    vwap = vwap,
                )
            },
            stockId,
            java.sql.Timestamp.from(startOfDay),
        )
    }
}

data class VwapResponse(
    val stockId: Long,
    val vwap: BigDecimal,
    val totalVolume: Long,
    val candleCount: Int,
    val since: Instant,
)

data class VwapPoint(val time: Long, val vwap: BigDecimal)
