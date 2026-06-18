package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.Candle
import com.monticker.api.marketdata.infrastructure.CandleRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class CandleService(private val candleRepository: CandleRepository) {

    fun getCandles(
        stockId: Long,
        interval: String = "1d",
        from: Instant = Instant.now().minus(30, ChronoUnit.DAYS),
        to: Instant = Instant.now(),
    ): List<Candle> {
        val table = when (interval) {
            "1m" -> "candles_1m"
            "1d" -> "candles_1d"
            else -> throw IllegalArgumentException("Unsupported interval: $interval")
        }
        return candleRepository.findCandles(stockId, table, from, to)
    }
}
