package com.monticker.worker.marketdata

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * candles_1d는 별도 배치 없이 여기서 매 1분봉 flush마다 당일(KST) 행을 함께 upsert한다.
 * ScreenerRepository의 prevClose 조회(candles_1d를 candle_time DESC로 OFFSET 1)가
 * "가장 최근 행 = 진행 중인 오늘, 그 다음 행 = 확정된 전일 종가"를 전제하므로,
 * 장마감 후 한 번만 적재하는 배치로는 장중 내내 전일 대비 등락률이 어긋난다.
 */
@Component
class CandleAggregator(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val KST = ZoneId.of("Asia/Seoul")

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

            val dayStart = c.minute.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()
            jdbc.update(
                """
                INSERT INTO candles_1d (stock_id, candle_time, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, candle_time) DO UPDATE SET
                    high   = GREATEST(candles_1d.high,  EXCLUDED.high),
                    low    = LEAST(candles_1d.low,   EXCLUDED.low),
                    close  = EXCLUDED.close,
                    volume = candles_1d.volume + EXCLUDED.volume
                """,
                c.stockId,
                Timestamp.from(dayStart),
                c.open, c.high, c.low, c.close,
                c.volume,
            )
        } catch (e: Exception) {
            log.error("Candle flush failed for stock {}: {}", c.stockId, e.message)
        }
    }

    // call at shutdown or periodically to flush current-minute candles
    fun flushAll() = state.values.forEach { flush(it) }

    /**
     * 앱 시작 시 1회 실행 — candles_1d가 비어 있으면(이 fix 이전에 쌓인 candles_1m 이력이
     * 있는 상태로 배포되는 경우) 지금까지의 candles_1m을 KST 달력일 기준으로 묶어 한 번에 채운다.
     * 이후에는 flush()의 실시간 upsert가 매일 새 행을 만들어가므로 별도 배치가 필요 없다.
     */
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    fun backfillOnStartup() {
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM candles_1d", Int::class.java) ?: 0
        if (count > 0) {
            log.info("candles_1d table has {} rows — skipping historical backfill", count)
            return
        }
        val rows = jdbc.update(
            """
            INSERT INTO candles_1d (stock_id, candle_time, open, high, low, close, volume)
            SELECT
                stock_id,
                date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul' AS day,
                (array_agg(open ORDER BY candle_time ASC))[1]  AS open,
                max(high)                                       AS high,
                min(low)                                        AS low,
                (array_agg(close ORDER BY candle_time DESC))[1] AS close,
                sum(volume)                                     AS volume
            FROM candles_1m
            GROUP BY stock_id, date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul')
            ON CONFLICT (stock_id, candle_time) DO NOTHING
            """,
        )
        log.info("candles_1d historical backfill from candles_1m complete: {} day-rows inserted", rows)
    }
}
