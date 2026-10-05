package com.monticker.api.marketdata.application

import com.monticker.api.common.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

/** 종목 하나의 장중 미니 시계열 — 10분 버킷의 마지막 1분봉 종가(오래된 → 최근) */
data class IntradaySeries(
    val stockId: Long,
    val closes: List<BigDecimal>,
    /** 마지막 1분봉 시각 */
    val lastTime: Instant?,
)

/**
 * 스크리너 "오늘" 스파크라인·관심종목 "오늘" 열용 장중 시계열을 여러 종목 한 번에 돌려준다(ADR-072).
 *
 * - 범위: 종목별 마지막 1분봉부터 거슬러 [SESSION_HOURS]시간 — 장이 닫혀 있으면 직전 거래일 장중이다.
 *   국내는 18:00~07:30 사이 틱이 없어(MarketSchedule) 이 창이 전날로 넘어가지 않는다.
 * - 크기: 10분 버킷으로 줄여 종목당 최대 66점. 종목 수는 [MAX_STOCKS]개.
 * - 캐시: 30초(CacheConfig.INTRADAY) — 같은 화면 여러 탭이 폴링해도 SQL은 30초에 한 번.
 */
@Service
class IntradaySeriesService(private val jdbc: JdbcTemplate) {

    companion object {
        const val MAX_STOCKS = 50
        const val BUCKET_SECONDS = 600
        const val SESSION_HOURS = 11

        /** 캐시 키를 순서·중복에 무관하게 만든다 */
        @JvmStatic
        fun normalizeIds(ids: List<Long>): List<Long> = ids.filter { it > 0 }.distinct().sorted().take(MAX_STOCKS)
    }

    @Cacheable(cacheNames = [CacheConfig.INTRADAY], key = "#stockIds.toString()")
    fun getSeries(stockIds: List<Long>): List<IntradaySeries> {
        if (stockIds.isEmpty()) return emptyList()
        require(stockIds.size <= MAX_STOCKS) { "종목은 최대 ${MAX_STOCKS}개까지 조회할 수 있습니다" }
        val placeholders = stockIds.joinToString(",") { "?" }
        // 상수(BUCKET_SECONDS·SESSION_HOURS)만 문자열로 들어간다. 종목 id는 바인딩한다.
        val rows = jdbc.query(
            """
            WITH last AS (
                SELECT s.id AS stock_id, l.last
                FROM stocks s
                JOIN LATERAL (
                    SELECT candle_time AS last FROM candles_1m WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1
                ) l ON true
                WHERE s.id IN ($placeholders)
            )
            SELECT DISTINCT ON (c.stock_id, bucket)
                   c.stock_id,
                   floor(extract(epoch FROM c.candle_time) / $BUCKET_SECONDS)::bigint AS bucket,
                   c.close,
                   c.candle_time
            FROM last
            JOIN candles_1m c
              ON c.stock_id = last.stock_id
             AND c.candle_time >  last.last - interval '$SESSION_HOURS hours'
             AND c.candle_time <= last.last
            ORDER BY c.stock_id, bucket, c.candle_time DESC
            """.trimIndent(),
            { rs, _ -> Triple(rs.getLong("stock_id"), rs.getBigDecimal("close"), rs.getTimestamp("candle_time").toInstant()) },
            *stockIds.toTypedArray(),
        )
        val byStock = rows.groupBy { it.first }
        return stockIds.map { id ->
            val points = byStock[id].orEmpty()   // 버킷 오름차순으로 온다
            IntradaySeries(id, points.map { it.second }, points.maxOfOrNull { it.third })
        }
    }
}
