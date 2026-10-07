package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.Candle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant

@Repository
class CandleRepository(private val jdbc: JdbcTemplate) {

    private val mapper = { rs: ResultSet, _: Int ->
        Candle(
            stockId = rs.getLong("stock_id"),
            open    = rs.getBigDecimal("open"),
            high    = rs.getBigDecimal("high"),
            low     = rs.getBigDecimal("low"),
            close   = rs.getBigDecimal("close"),
            volume  = rs.getLong("volume"),
            time    = rs.getTimestamp("candle_time").toInstant(),
        )
    }

    /**
     * 구간 안의 **가장 최근** [limit]개를 시간 오름차순으로 돌려준다. 예전엔 `ORDER BY ASC LIMIT`이라 구간이 limit보다
     * 길면(1분봉 30일, 일봉 1년) 가장 오래된 300개만 와서 차트 오른쪽(최근)이 비었다.
     */
    fun findCandles(stockId: Long, table: String, from: Instant, to: Instant, limit: Int = 300): List<Candle> {
        val allowed = setOf("candles_1m", "candles_1d")
        require(table in allowed) { "Invalid candle table: $table" }

        return jdbc.query(
            """
            SELECT stock_id, open, high, low, close, volume, candle_time
            FROM $table
            WHERE stock_id = ? AND candle_time BETWEEN ? AND ?
            ORDER BY candle_time DESC
            LIMIT ?
            """,
            mapper,
            stockId,
            java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(to),
            limit,
        ).asReversed()
    }

    /**
     * ADR-076 — 분봉을 [bucketMinutes]분 봉으로 조회 시점에 묶는다(3분·15분·1시간). `date_bin`은 PostgreSQL 14+ 내장이라
     * TimescaleDB 없이도 동작한다. 기준점(UTC 2000-01-01 00:00)이 정시라 KST(+9h) 정시·15분 경계와 맞는다.
     * 시가 = 버킷 첫 분봉의 시가, 종가 = 마지막 분봉의 종가, 고저 = max/min, 거래량 = 합.
     */
    fun findBucketed(stockId: Long, bucketMinutes: Int, from: Instant, to: Instant, limit: Int = 300): List<Candle> {
        require(bucketMinutes in ALLOWED_BUCKETS) { "Invalid bucket: $bucketMinutes" }
        return jdbc.query(
            """
            SELECT stock_id, open, high, low, close, volume, bucket AS candle_time FROM (
                SELECT stock_id,
                       date_bin(CAST(? AS interval), candle_time, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS bucket,
                       (array_agg(open  ORDER BY candle_time ASC))[1]  AS open,
                       max(high)                                       AS high,
                       min(low)                                        AS low,
                       (array_agg(close ORDER BY candle_time DESC))[1] AS close,
                       sum(volume)                                     AS volume
                FROM candles_1m
                WHERE stock_id = ? AND candle_time BETWEEN ? AND ?
                GROUP BY stock_id, bucket
                ORDER BY bucket DESC
                LIMIT ?
            ) b
            ORDER BY candle_time ASC
            """,
            mapper,
            "$bucketMinutes minutes",
            stockId,
            java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(to),
            limit,
        )
    }

    companion object {
        val ALLOWED_BUCKETS = setOf(3, 15, 60)
    }

    /**
     * [before] 이전의 가장 최근 일봉 종가 — 전 거래일 종가. candles_1d.candle_time은 KST 자정이다(worker CandleAggregator).
     * 연휴보다 긴 공백([lookbackDays] 초과)이면 직전 거래일이 아니라고 보고 null.
     */
    fun findPreviousClose(stockId: Long, before: Instant, lookbackDays: Long = 10): BigDecimal? =
        jdbc.queryForList(
            """SELECT close FROM candles_1d WHERE stock_id = ? AND candle_time < ? AND candle_time >= ?
               ORDER BY candle_time DESC LIMIT 1""",
            BigDecimal::class.java, stockId, java.sql.Timestamp.from(before), java.sql.Timestamp.from(before.minusSeconds(lookbackDays * 86_400)),
        ).firstOrNull()?.takeIf { it.signum() > 0 }
}
