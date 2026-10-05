package com.monticker.api.screener.infrastructure

import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.domain.ScreenerItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant

/** 페이지에 오른 종목의 보조 정보(거래량 배수·오늘 이벤트) */
data class ScreenerDayContext(val volumeMultiple: Double?, val todayEvents: List<String>)

/** 섹터와 활성 종목 수 */
data class SectorCount(val sector: String, val count: Int)

@Repository
class ScreenerRepository(private val jdbc: JdbcTemplate) {

    companion object {
        // Quant Lab 유니버스 필터(ScreenerService.search)도 동일한 기준을 쓴다 — 두 곳에서
        // 따로 정의하면 브라우즈 목록과 검색 결과의 시가총액 구간 경계가 어긋날 수 있다.
        const val MARKET_CAP_LARGE_THRESHOLD = 1_000_000_000_000L  // 1조원
        const val MARKET_CAP_MID_THRESHOLD   = 100_000_000_000L    // 1000억원
        /** 거래량 배수의 기준 기간(거래일) */
        const val VOLUME_BASELINE_DAYS = 20

        fun matchesMarket(market: String, itemMarket: String): Boolean = when (market) {
            "domestic" -> itemMarket in setOf("KOSPI", "KOSDAQ")
            "overseas" -> itemMarket in setOf("NASDAQ", "NYSE")
            else       -> true
        }

        fun matchesMarketCapTier(tier: String, marketCap: Long?): Boolean = when (tier) {
            "large" -> (marketCap ?: 0) >= MARKET_CAP_LARGE_THRESHOLD
            "mid"   -> (marketCap ?: 0) in MARKET_CAP_MID_THRESHOLD until MARKET_CAP_LARGE_THRESHOLD
            "small" -> (marketCap ?: 0) < MARKET_CAP_MID_THRESHOLD
            else    -> true
        }

        /** 거래량 배수 = 최신 일봉 거래량 ÷ 직전 N거래일 평균. 평균이 0이거나 없으면 null */
        fun volumeMultiple(dayVolume: Long?, avgVolume: Double?): Double? =
            if (dayVolume == null || avgVolume == null || avgVolume <= 0.0) null else dayVolume / avgVolume
    }

    /**
     * WHERE/조인 조각과 바인딩 값. 사용자 입력은 모두 `?`로 바인딩하고, SQL에 직접 들어가는 것은
     * 화이트리스트를 통과한 market/tier/sort 상수와 이 클래스의 정수 상수뿐이다.
     */
    private class Built(val sql: String, val args: List<Any>)

    private fun marketFilter(market: String) = when (market) {
        "domestic" -> "AND s.market IN ('KOSPI', 'KOSDAQ')"
        "overseas" -> "AND s.market IN ('NASDAQ', 'NYSE')"
        else       -> ""
    }

    private fun marketCapTierFilter(tier: String): String = when (tier) {
        "large" -> "AND sf.market_cap >= $MARKET_CAP_LARGE_THRESHOLD"
        "mid"   -> "AND sf.market_cap >= $MARKET_CAP_MID_THRESHOLD AND sf.market_cap < $MARKET_CAP_LARGE_THRESHOLD"
        "small" -> "AND sf.market_cap < $MARKET_CAP_MID_THRESHOLD"
        else    -> ""
    }

    private fun orderBy(sort: String) = when (sort) {
        "volume"  -> "volume DESC"
        "rise"    -> "change_pct DESC"
        "fall"    -> "change_pct ASC"
        "volmult" -> "vol_mult DESC"
        else      -> "amount DESC"
    }

    /**
     * 조건에 맞는 종목 집합 q를 만드는 CTE. 거래량 배수용 일봉 LATERAL은 필터·정렬에 필요할 때만 붙인다 —
     * 표시용 배수는 페이지에 오른 종목만 [findDayContext]로 따로 계산한다(전 종목 × 20일 평균을 매번 돌리지 않는다).
     */
    private fun buildBase(c: ScreenerCriteria, stockIdIn: Set<Long>?, todayStart: Instant, activeOnly: Boolean = true): Built {
        val args = ArrayList<Any>()
        val active = if (activeOnly) "s.is_active = true" else "true"
        val where = StringBuilder("WHERE $active ${marketFilter(c.market)} ${marketCapTierFilter(c.marketCapTier)}")
        if (c.sectors.isNotEmpty()) {
            where.append(" AND s.sector IN (${c.sectors.joinToString(",") { "?" }})")
            args.addAll(c.sectors)
        }
        for (type in c.stockEventTypes) {
            where.append(" AND EXISTS (SELECT 1 FROM stock_events e WHERE e.stock_id = s.id AND e.event_type = ? AND e.event_time >= ?)")
            args += type; args += Timestamp.from(todayStart)
        }
        if (stockIdIn != null) {
            // 빈 집합은 호출부가 먼저 걸러 SQL까지 오지 않는다
            where.append(" AND s.id IN (${stockIdIn.joinToString(",") { "?" }})")
            args.addAll(stockIdIn)
        }
        val volJoin = if (c.needsVolumeMultipleInQuery) """
            LEFT JOIN LATERAL (
                SELECT volume FROM candles_1d WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1
            ) d0 ON true
            LEFT JOIN LATERAL (
                SELECT AVG(volume)::float8 AS avg_volume FROM (
                    SELECT volume FROM candles_1d WHERE stock_id = s.id
                    ORDER BY candle_time DESC LIMIT $VOLUME_BASELINE_DAYS OFFSET 1
                ) x
            ) d20 ON true
        """ else ""
        val volCols = if (c.needsVolumeMultipleInQuery)
            "d0.volume AS day_volume, d20.avg_volume AS avg_day_volume, CASE WHEN d20.avg_volume > 0 THEN d0.volume / d20.avg_volume END AS vol_mult"
        else "NULL::bigint AS day_volume, NULL::float8 AS avg_day_volume, NULL::float8 AS vol_mult"

        val sql = """
            WITH q AS (
                SELECT
                    s.id          AS stock_id,
                    s.symbol,
                    s.name,
                    s.market,
                    s.sector,
                    COALESCE(c.close, 0)              AS price,
                    COALESCE(c.volume, 0)             AS volume,
                    COALESCE(c.close * c.volume, 0)   AS amount,
                    prev.close                        AS prev_close,
                    (c.close - prev.close) / NULLIF(prev.close, 0) * 100 AS change_pct,
                    sf.market_cap, sf.per, sf.pbr, sf.is_mocked AS fundamentals_mocked,
                    $volCols
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
                $volJoin
                LEFT JOIN stock_fundamentals sf ON sf.stock_id = s.id
                $where
            )
        """.trimIndent()
        return Built(sql, args)
    }

    private fun computedFilter(c: ScreenerCriteria): Built {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        c.minChange?.let { parts += "change_pct >= ?"; args += it }
        c.maxChange?.let { parts += "change_pct <= ?"; args += it }
        c.minVolMult?.let { parts += "vol_mult >= ?"; args += it }
        return Built(if (parts.isEmpty()) "" else "WHERE " + parts.joinToString(" AND "), args)
    }

    fun findItems(
        criteria: ScreenerCriteria,
        limit: Int,
        offset: Int,
        stockIdIn: Set<Long>? = null,
        todayStart: Instant = Instant.EPOCH,
    ): List<ScreenerItem> {
        if (stockIdIn != null && stockIdIn.isEmpty()) return emptyList()
        val base = buildBase(criteria, stockIdIn, todayStart)
        val filter = computedFilter(criteria)
        val sql = "${base.sql}\nSELECT * FROM q ${filter.sql} ORDER BY ${orderBy(criteria.sort)} NULLS LAST LIMIT ? OFFSET ?"
        val args = base.args + filter.args + listOf(limit, offset)
        return jdbc.query(sql, { rs, rowNum -> mapRow(rs, offset + rowNum + 1) }, *args.toTypedArray())
    }

    fun count(
        criteria: ScreenerCriteria,
        stockIdIn: Set<Long>? = null,
        todayStart: Instant = Instant.EPOCH,
    ): Int {
        if (stockIdIn != null && stockIdIn.isEmpty()) return 0
        // 계산 컬럼 조건이 없으면 LATERAL 없이 센다(기존 경로와 같은 비용)
        if (!criteria.hasComputedFilter) {
            val args = ArrayList<Any>()
            val where = StringBuilder("WHERE s.is_active = true ${marketFilter(criteria.market)} ${marketCapTierFilter(criteria.marketCapTier)}")
            if (criteria.sectors.isNotEmpty()) {
                where.append(" AND s.sector IN (${criteria.sectors.joinToString(",") { "?" }})"); args.addAll(criteria.sectors)
            }
            for (type in criteria.stockEventTypes) {
                where.append(" AND EXISTS (SELECT 1 FROM stock_events e WHERE e.stock_id = s.id AND e.event_type = ? AND e.event_time >= ?)")
                args += type; args += Timestamp.from(todayStart)
            }
            if (stockIdIn != null) { where.append(" AND s.id IN (${stockIdIn.joinToString(",") { "?" }})"); args.addAll(stockIdIn) }
            val joinFundamentals = if (criteria.marketCapTier != "all") "LEFT JOIN stock_fundamentals sf ON sf.stock_id = s.id" else ""
            return jdbc.queryForObject("SELECT COUNT(*) FROM stocks s $joinFundamentals $where", Int::class.java, *args.toTypedArray()) ?: 0
        }
        val base = buildBase(criteria, stockIdIn, todayStart)
        val filter = computedFilter(criteria)
        return jdbc.queryForObject("${base.sql}\nSELECT COUNT(*) FROM q ${filter.sql}", Int::class.java,
            *(base.args + filter.args).toTypedArray()) ?: 0
    }

    private fun mapRow(rs: java.sql.ResultSet, rank: Int): ScreenerItem {
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
        val marketCap = rs.getLong("market_cap").let { if (rs.wasNull()) null else it }
        val volMult = rs.getDouble("vol_mult").let { if (rs.wasNull()) null else it }

        return ScreenerItem(
            rank         = rank,
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
            marketCap    = marketCap,
            per          = rs.getBigDecimal("per"),
            pbr          = rs.getBigDecimal("pbr"),
            isFundamentalsMocked = rs.getBoolean("fundamentals_mocked"),
            volumeMultiple = volMult,
        )
    }

    /**
     * ES 종목 검색 결과(ID 목록)를 기반으로 시세 데이터를 조회한다.
     */
    fun findItemsByStockIds(
        stockIds: List<Long>,
        sort: String = "amount",
    ): List<ScreenerItem> {
        if (stockIds.isEmpty()) return emptyList()
        val criteria = ScreenerCriteria(sort = if (sort in ScreenerCriteria.SORTS) sort else "amount")
        // is_active와 무관하게 요청한 종목은 보여준다(관심종목에 상장폐지 종목이 남아 있을 수 있다 — 기존 동작)
        val base = buildBase(criteria, stockIds.toSet(), Instant.EPOCH, activeOnly = false)
        val sql = base.sql + "\nSELECT * FROM q ORDER BY ${orderBy(criteria.sort)} NULLS LAST"
        val rows = jdbc.query(sql, { rs, rowNum -> mapRow(rs, rowNum + 1) }, *base.args.toTypedArray())
        return rows.mapIndexed { idx, item -> item.copy(rank = idx + 1) }
    }

    /**
     * 페이지에 오른 종목들의 거래량 배수와 오늘 이벤트 유형. 종목 수는 호출부가 50개 이하로 제한한다.
     */
    fun findDayContext(stockIds: Collection<Long>, todayStart: Instant): Map<Long, ScreenerDayContext> {
        if (stockIds.isEmpty()) return emptyMap()
        val ids = stockIds.distinct()
        val placeholders = ids.joinToString(",") { "?" }
        val vol = jdbc.query(
            """
            SELECT s.id AS stock_id, d0.volume AS day_volume, d20.avg_volume
            FROM stocks s
            LEFT JOIN LATERAL (
                SELECT volume FROM candles_1d WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1
            ) d0 ON true
            LEFT JOIN LATERAL (
                SELECT AVG(volume)::float8 AS avg_volume FROM (
                    SELECT volume FROM candles_1d WHERE stock_id = s.id
                    ORDER BY candle_time DESC LIMIT $VOLUME_BASELINE_DAYS OFFSET 1
                ) x
            ) d20 ON true
            WHERE s.id IN ($placeholders)
            """.trimIndent(),
            { rs, _ ->
                val dayVol = rs.getLong("day_volume").let { if (rs.wasNull()) null else it }
                val avg = rs.getDouble("avg_volume").let { if (rs.wasNull()) null else it }
                rs.getLong("stock_id") to volumeMultiple(dayVol, avg)
            },
            *ids.toTypedArray(),
        ).toMap()
        val events = jdbc.query(
            """
            SELECT stock_id, event_type FROM stock_events
            WHERE stock_id IN ($placeholders) AND event_time >= ?
            GROUP BY stock_id, event_type
            ORDER BY stock_id, MAX(importance_score) DESC
            """.trimIndent(),
            { rs, _ -> rs.getLong("stock_id") to rs.getString("event_type") },
            *(ids + Timestamp.from(todayStart)).toTypedArray(),
        ).groupBy({ it.first }, { it.second })
        return ids.associateWith { ScreenerDayContext(vol[it], events[it] ?: emptyList()) }
    }

    /** 활성 종목의 섹터와 종목 수(많은 순). 섹터 칩 목록용 */
    fun findSectors(market: String): List<SectorCount> = jdbc.query(
        """
        SELECT s.sector, COUNT(*) AS cnt FROM stocks s
        WHERE s.is_active = true AND s.sector IS NOT NULL AND s.sector <> '' ${marketFilter(market)}
        GROUP BY s.sector ORDER BY cnt DESC, s.sector ASC
        LIMIT 100
        """.trimIndent(),
    ) { rs, _ -> SectorCount(rs.getString("sector"), rs.getInt("cnt")) }
}
