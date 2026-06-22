package com.monticker.worker.marketdata

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

@Component
class PriceTickDbWriter(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(tick: GeneratedTick) {
        try {
            jdbc.update(
                """
                INSERT INTO price_ticks (stock_id, price, volume, trade_time)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (stock_id, trade_time) DO NOTHING
                """,
                tick.stockId,
                tick.price,
                tick.volume,
                Timestamp.from(tick.tradeTime),
            )
        } catch (e: Exception) {
            log.debug("price_ticks insert failed: {}", e.message)
        }
    }
}
