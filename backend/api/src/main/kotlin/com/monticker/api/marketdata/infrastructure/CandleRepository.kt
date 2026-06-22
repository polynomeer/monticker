package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.Candle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class CandleRepository(private val jdbc: JdbcTemplate) {

    fun findCandles(stockId: Long, table: String, from: Instant, to: Instant, limit: Int = 300): List<Candle> {
        val allowed = setOf("candles_1m", "candles_1d")
        require(table in allowed) { "Invalid candle table: $table" }

        // CAgg view 이름 매핑
        val caggView = "${table}_cagg"

        // CAgg view 존재하면 우선 사용
        val viewExists = caggViewExists(caggView)
        val source = if (viewExists) caggView else table

        return query(source, stockId, from, to, limit)
    }

    private fun caggViewExists(viewName: String): Boolean =
        try {
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.views
                WHERE table_name = ?
                UNION ALL
                SELECT COUNT(*) FROM pg_matviews WHERE matviewname = ?
                LIMIT 1
                """,
                Int::class.java,
                viewName,
                viewName,
            ) ?: 0 > 0
        } catch (_: Exception) { false }

    private fun query(source: String, stockId: Long, from: Instant, to: Instant, limit: Int): List<Candle> =
        jdbc.query(
            """
            SELECT stock_id, open, high, low, close, volume, candle_time
            FROM $source
            WHERE stock_id = ? AND candle_time BETWEEN ? AND ?
            ORDER BY candle_time ASC
            LIMIT ?
            """,
            { rs: ResultSet, _ ->
                Candle(
                    stockId = rs.getLong("stock_id"),
                    open    = rs.getBigDecimal("open"),
                    high    = rs.getBigDecimal("high"),
                    low     = rs.getBigDecimal("low"),
                    close   = rs.getBigDecimal("close"),
                    volume  = rs.getLong("volume"),
                    time    = rs.getTimestamp("candle_time").toInstant(),
                )
            },
            stockId,
            java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(to),
            limit,
        )
}
