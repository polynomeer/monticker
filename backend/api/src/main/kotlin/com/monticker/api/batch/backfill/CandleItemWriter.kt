package com.monticker.api.batch.backfill

import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp

class CandleItemWriter(private val jdbc: JdbcTemplate) : ItemWriter<CandleOhlcv> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun write(chunk: Chunk<out CandleOhlcv>) {
        val items = chunk.items
        if (items.isEmpty()) return

        // 일봉을 candles_1m에 넣을 때 해당 날 09:00 KST(UTC+9) 기준 타임스탬프 사용
        jdbc.batchUpdate(
            """
            INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
            VALUES (?, (? AT TIME ZONE 'Asia/Seoul'), ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id, candle_time) DO NOTHING
            """.trimIndent(),
            items.map { c ->
                arrayOf(
                    c.stockId,
                    Timestamp.valueOf(c.date.atTime(9, 0)),
                    c.open, c.high, c.low, c.close, c.volume,
                )
            },
        )

        log.debug("CandleBackfill: wrote {} candles for stockId={}", items.size, items.first().stockId)
    }
}
