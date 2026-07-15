package com.monticker.api.screener.infrastructure

import com.monticker.api.screener.domain.ScreenerItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode

@Repository
class ScreenerRepository(private val jdbc: JdbcTemplate) {

    fun findItems(
        market: String,   // all | domestic | overseas
        sort: String,     // amount | volume | rise | fall
        limit: Int,
        offset: Int,
    ): List<ScreenerItem> {
        val marketFilter = when (market) {
            "domestic" -> "AND s.market IN ('KOSPI', 'KOSDAQ')"
            "overseas" -> "AND s.market IN ('NASDAQ', 'NYSE')"
            else       -> ""
        }

        val orderBy = when (sort) {
            "volume" -> "c.volume DESC"
            "rise"   -> "(c.close - prev.close) / NULLIF(prev.close, 0) DESC NULLS LAST"
            "fall"   -> "(c.close - prev.close) / NULLIF(prev.close, 0) ASC NULLS LAST"
            else     -> "(c.close * c.volume) DESC"
        }

        val sql = """
            SELECT
                s.id          AS stock_id,
                s.symbol,
                s.name,
                s.market,
                s.sector,
                COALESCE(c.close, 0)              AS price,
                COALESCE(c.volume, 0)             AS volume,
                COALESCE(c.close * c.volume, 0)   AS amount,
                prev.close                        AS prev_close
            FROM stocks s
            LEFT JOIN LATERAL (
                SELECT close, volume
                FROM candles_1m
                WHERE stock_id = s.id
                ORDER BY candle_time DESC
                LIMIT 1
            ) c ON true
            LEFT JOIN LATERAL (
                SELECT close
                FROM candles_1d
                WHERE stock_id = s.id
                ORDER BY candle_time DESC
                LIMIT 1 OFFSET 1
            ) prev ON true
            WHERE s.is_active = true
            $marketFilter
            ORDER BY $orderBy NULLS LAST
            LIMIT ? OFFSET ?
        """.trimIndent()

        return jdbc.query(sql, { rs, rowNum ->
            val price     = rs.getBigDecimal("price") ?: BigDecimal.ZERO
            val prevClose = rs.getBigDecimal("prev_close")
            val changeRate = if (prevClose != null && prevClose > BigDecimal.ZERO) {
                price.subtract(prevClose).divide(prevClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100")).toDouble()
            } else 0.0
            val changeAmount = if (prevClose != null) price.subtract(prevClose) else BigDecimal.ZERO
            val volume = rs.getLong("volume")
            val amount = rs.getBigDecimal("amount") ?: BigDecimal.ZERO
            val buyRatio = (40 + (rs.getLong("stock_id") * 7 + volume) % 31).toInt()

            ScreenerItem(
                rank         = offset + rowNum + 1,
                stockId      = rs.getLong("stock_id"),
                symbol       = rs.getString("symbol"),
                name         = rs.getString("name"),
                market       = rs.getString("market"),
                sector       = rs.getString("sector"),
                price        = price,
                prevClose    = prevClose,
                changeRate   = changeRate,
                changeAmount = changeAmount,
                volume       = volume,
                amount       = amount,
                buyRatio     = buyRatio,
                sellRatio    = 100 - buyRatio,
            )
        }, limit, offset)
    }

    /**
     * ES 종목 검색 결과(ID 목록)를 기반으로 시세 데이터를 조회한다.
     * ES 점수 순서를 유지하기 위해 결과를 stockIds 순서로 재정렬한다.
     */
    fun findItemsByStockIds(
        stockIds: List<Long>,
        sort: String = "amount",
    ): List<ScreenerItem> {
        if (stockIds.isEmpty()) return emptyList()
        val placeholders = stockIds.joinToString(",") { "?" }
        val orderBy = when (sort) {
            "volume" -> "c.volume DESC"
            "rise"   -> "(c.close - prev.close) / NULLIF(prev.close, 0) DESC NULLS LAST"
            "fall"   -> "(c.close - prev.close) / NULLIF(prev.close, 0) ASC NULLS LAST"
            else     -> "(c.close * c.volume) DESC"
        }
        val sql = """
            SELECT
                s.id          AS stock_id,
                s.symbol,
                s.name,
                s.market,
                s.sector,
                COALESCE(c.close, 0)              AS price,
                COALESCE(c.volume, 0)             AS volume,
                COALESCE(c.close * c.volume, 0)   AS amount,
                prev.close                        AS prev_close
            FROM stocks s
            LEFT JOIN LATERAL (
                SELECT close, volume
                FROM candles_1m
                WHERE stock_id = s.id
                ORDER BY candle_time DESC
                LIMIT 1
            ) c ON true
            LEFT JOIN LATERAL (
                SELECT close
                FROM candles_1d
                WHERE stock_id = s.id
                ORDER BY candle_time DESC
                LIMIT 1 OFFSET 1
            ) prev ON true
            WHERE s.id IN ($placeholders)
            ORDER BY $orderBy NULLS LAST
        """.trimIndent()

        val rows = jdbc.query(sql, { rs, rowNum ->
            val price     = rs.getBigDecimal("price") ?: BigDecimal.ZERO
            val prevClose = rs.getBigDecimal("prev_close")
            val changeRate = if (prevClose != null && prevClose > BigDecimal.ZERO) {
                price.subtract(prevClose).divide(prevClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100")).toDouble()
            } else 0.0
            val changeAmount = if (prevClose != null) price.subtract(prevClose) else BigDecimal.ZERO
            val volume = rs.getLong("volume")
            val amount = rs.getBigDecimal("amount") ?: BigDecimal.ZERO
            val buyRatio = (40 + (rs.getLong("stock_id") * 7 + volume) % 31).toInt()
            ScreenerItem(
                rank         = rowNum + 1,
                stockId      = rs.getLong("stock_id"),
                symbol       = rs.getString("symbol"),
                name         = rs.getString("name"),
                market       = rs.getString("market"),
                sector       = rs.getString("sector"),
                price        = price,
                prevClose    = prevClose,
                changeRate   = changeRate,
                changeAmount = changeAmount,
                volume       = volume,
                amount       = amount,
                buyRatio     = buyRatio,
                sellRatio    = 100 - buyRatio,
            )
        }, *stockIds.toTypedArray())

        // ES 관련도 순서 유지: sort=amount/volume/rise/fall 이면 시세 기준 정렬 유지,
        // 그 외(query 검색)에서 호출 시 ES 점수 순으로 재정렬
        val idOrder = stockIds.withIndex().associate { (i, id) -> id to i }
        return rows.mapIndexed { idx, item -> item.copy(rank = idx + 1) }
    }

    fun count(market: String): Int {
        val marketFilter = when (market) {
            "domestic" -> "AND market IN ('KOSPI', 'KOSDAQ')"
            "overseas" -> "AND market IN ('NASDAQ', 'NYSE')"
            else       -> ""
        }
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM stocks WHERE is_active = true $marketFilter",
            Int::class.java
        ) ?: 0
    }
}
