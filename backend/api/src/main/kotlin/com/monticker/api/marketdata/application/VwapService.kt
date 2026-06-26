package com.monticker.api.marketdata.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * VWAP (Volume Weighted Average Price) 계산 서비스.
 *
 * VWAP = Σ(가격 × 거래량) / Σ(거래량)
 *
 * 당일 candles_1m 데이터를 기반으로 계산한다.
 * 알고리즘 트레이딩에서 기준선으로 활용되며, 시장 평균 체결 가격을 나타낸다.
 */
@Service
class VwapService(private val jdbc: JdbcTemplate) {

    /** 당일 단일 VWAP 값 */
    fun getDailyVwap(stockId: Long): VwapResponse {
        val startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS)

        val row = jdbc.queryForMap(
            """
            SELECT
                COALESCE(SUM(close * volume), 0) AS price_volume,
                COALESCE(SUM(volume), 0)         AS total_volume,
                COUNT(*)                          AS candle_count
            FROM candles_1m
            WHERE stock_id = ? AND candle_time >= ?
            """,
            stockId,
            java.sql.Timestamp.from(startOfDay),
        )

        val priceVolume = (row["price_volume"] as? Number)?.toDouble() ?: 0.0
        val totalVolume = (row["total_volume"] as? Number)?.toLong()  ?: 0L
        val candleCount = (row["candle_count"] as? Number)?.toInt()   ?: 0

        val vwap = if (totalVolume > 0)
            BigDecimal(priceVolume / totalVolume).setScale(4, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        return VwapResponse(stockId, vwap, totalVolume, candleCount, startOfDay)
    }

    /** 누적 VWAP 시계열 — 차트 오버레이용 */
    fun getVwapSeries(stockId: Long): List<VwapPoint> {
        val startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS)

        return jdbc.query(
            """
            SELECT
                candle_time,
                SUM(close * volume) OVER (ORDER BY candle_time) AS cum_pv,
                SUM(volume)         OVER (ORDER BY candle_time) AS cum_vol
            FROM candles_1m
            WHERE stock_id = ? AND candle_time >= ?
            ORDER BY candle_time
            """,
            { rs, _ ->
                val cumPv  = rs.getDouble("cum_pv")
                val cumVol = rs.getLong("cum_vol")
                val vwap   = if (cumVol > 0)
                    BigDecimal(cumPv / cumVol).setScale(4, RoundingMode.HALF_UP)
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
