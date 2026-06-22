package com.monticker.worker.marketdata

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class CandleAggregator(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    // in-memory OHLCV per (stockId, minute)
    private val state = mutableMapOf<Long, CandleState>()

    data class CandleState(
        val stockId: Long,
        val minute: Instant,
        var open: BigDecimal,
        var high: BigDecimal,
        var low: BigDecimal,
        var close: BigDecimal,
        var volume: Long,
    )

    fun onTick(tick: GeneratedTick) {
        val minute = tick.tradeTime.truncatedTo(ChronoUnit.MINUTES)
        val prev = state[tick.stockId]

        if (prev == null || prev.minute != minute) {
            // flush previous candle if exists
            prev?.let { flush(it) }
            state[tick.stockId] = CandleState(
                stockId = tick.stockId,
                minute  = minute,
                open    = tick.price,
                high    = tick.price,
                low     = tick.price,
                close   = tick.price,
                volume  = tick.volume,
            )
        } else {
            prev.high   = maxOf(prev.high, tick.price)
            prev.low    = minOf(prev.low, tick.price)
            prev.close  = tick.price
            prev.volume += tick.volume
        }
    }

    private fun flush(c: CandleState) {
        try {
            jdbc.update(
                """
                INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, candle_time) DO UPDATE SET
                    high   = GREATEST(candles_1m.high,  EXCLUDED.high),
                    low    = LEAST(candles_1m.low,   EXCLUDED.low),
                    close  = EXCLUDED.close,
                    volume = candles_1m.volume + EXCLUDED.volume
                """,
                c.stockId,
                Timestamp.from(c.minute),
                c.open, c.high, c.low, c.close,
                c.volume,
            )
        } catch (e: Exception) {
            log.error("Candle flush failed for stock {}: {}", c.stockId, e.message)
        }
    }

    // call at shutdown or periodically to flush current-minute candles
    fun flushAll() = state.values.forEach { flush(it) }
}
