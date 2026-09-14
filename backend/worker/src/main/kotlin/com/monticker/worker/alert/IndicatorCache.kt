package com.monticker.worker.alert

import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-044 — 룰 평가에 쓰는 지표를 종목별로 캐시한다.
 *
 * RSI·이동평균·거래량 평균·평단가는 전부 candles_1d/positions에서 계산되며 **틱마다 변하지 않는다**
 * (일봉은 분 단위로 갱신되고, avg_vol은 장중 상수다). 이전에는 룰마다 틱마다 다시 계산했다 —
 * VOLUME_SURGE는 20일 집계 서브쿼리 2개를 틱 × 룰 수만큼 돌렸다.
 * TTL 60초: 종목당 지표당 분당 1회로 상한이 걸린다. SQL과 계산은 이전 AlertEvaluator에서 그대로 옮겼다.
 */
class IndicatorCache(
    private val jdbc: JdbcTemplate,
    private val ttlMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry<T>(val value: T, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry<Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, compute: () -> T): T {
        val now = clock()
        cache[key]?.let { if (now - it.at < ttlMs) return it.value as T }
        val v = compute()
        cache[key] = Entry(v, now)
        return v
    }

    fun evict(stockId: Long) { cache.keys.removeIf { it.endsWith(":$stockId") || it.contains(":$stockId:") } }
    fun size(): Int = cache.size

    /** candles_1d 종가로 RSI(period). Wilder smoothing — backend/api IndicatorEngine.rsi()와 동일 알고리즘. */
    fun rsi(stockId: Long, period: Int): Double? = cached("rsi:$period:$stockId") {
        val closes = jdbc.query(
            "SELECT close FROM candles_1d WHERE stock_id = ? ORDER BY candle_time DESC LIMIT ?",
            { rs, _ -> rs.getBigDecimal("close").toDouble() },
            stockId, period * 3,
        ).asReversed()
        if (closes.size <= period) return@cached null
        val changes = closes.zipWithNext { a, b -> b - a }
        var avgGain = changes.subList(0, period).filter { it > 0 }.sum() / period
        var avgLoss = changes.subList(0, period).filter { it < 0 }.map { -it }.sum() / period
        for (i in period until changes.size) {
            val change = changes[i]
            avgGain = (avgGain * (period - 1) + (if (change > 0) change else 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + (if (change < 0) -change else 0.0)) / period
        }
        if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)
    }

    fun movingAverage(stockId: Long, period: Int): Double? = cached("ma:$period:$stockId") {
        jdbc.queryForObject(
            """
                    SELECT AVG(close) FROM (
                        SELECT close FROM candles_1d WHERE stock_id = ?
                        ORDER BY candle_time DESC LIMIT ?
                    ) t
                    """,
            Double::class.java, stockId, period,
        )
    }

    /** (당일 거래량, N일 평균 거래량). 장 초반일수록 today_vol이 구조적으로 작다 — 시간대 정규화는 하지 않았다. */
    fun volumeStats(stockId: Long, period: Int): Pair<Long, Double> = cached("vol:$period:$stockId") {
        val row = jdbc.queryForMap(
            """
                    SELECT
                        (SELECT volume FROM candles_1d
                           WHERE stock_id = ? AND candle_time >= DATE_TRUNC('day', NOW() AT TIME ZONE 'Asia/Seoul')
                        ) AS today_vol,
                        (SELECT AVG(volume) FROM candles_1d
                           WHERE stock_id = ?
                             AND candle_time >= NOW() - INTERVAL '$period days'
                             AND candle_time < DATE_TRUNC('day', NOW() AT TIME ZONE 'Asia/Seoul')
                        ) AS avg_vol
                    """,
            stockId, stockId,
        )
        val todayVol = (row["today_vol"] as? Number)?.toLong() ?: 0L
        val avgVol   = (row["avg_vol"] as? Number)?.toDouble() ?: 1.0
        todayVol to avgVol
    }

    /** 보유 중(net_qty > 0)일 때만 평단가. 없으면 null — 평가할 기준이 없다. */
    fun avgBuyPrice(userId: Long, stockId: Long): BigDecimal? = cached("avgbuy:$userId:$stockId") {
        jdbc.query(
            "SELECT avg_buy_price FROM portfolio_positions WHERE user_id = ? AND stock_id = ? AND net_qty > 0",
            { rs, _ -> rs.getBigDecimal("avg_buy_price") },
            userId, stockId,
        ).firstOrNull()
    }
}
