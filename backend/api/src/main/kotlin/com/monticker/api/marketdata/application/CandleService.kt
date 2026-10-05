package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.Candle
import com.monticker.api.marketdata.infrastructure.CandleRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class CandleService(private val candleRepository: CandleRepository) {

    companion object {
        /** interval → (버킷 분, 기본 조회 일수) */
        val BUCKETS: Map<String, Pair<Int, Long>> = mapOf("3m" to (3 to 5L), "15m" to (15 to 15L), "1h" to (60 to 45L))
    }

    fun getCandles(
        stockId: Long,
        interval: String = "1d",
        from: Instant = Instant.now().minus(30, ChronoUnit.DAYS),
        to: Instant = Instant.now(),
    ): List<Candle> {
        // ADR-076 — 3분·15분·1시간은 분봉을 조회 시점에 묶는다. 기본 구간은 버킷 300개를 넉넉히 덮는 길이다.
        BUCKETS[interval]?.let { (minutes, defaultDays) ->
            val effectiveFrom = maxOf(from, Instant.now().minus(defaultDays, ChronoUnit.DAYS))
            return candleRepository.findBucketed(stockId, minutes, effectiveFrom, to)
        }
        val table = when (interval) {
            "1m"  -> "candles_1m"
            "1d"  -> "candles_1d"
            "1w"  -> "candles_1w"
            "1M"  -> "candles_1M"
            "3M"  -> "candles_1M"
            "1Y"  -> "candles_1d"
            else  -> throw IllegalArgumentException("Unsupported interval: $interval")
        }
        val effectiveFrom = when (interval) {
            "1w" -> Instant.now().minus(90, ChronoUnit.DAYS)
            "1M" -> Instant.now().minus(365, ChronoUnit.DAYS)
            "3M" -> Instant.now().minus(365 * 3L, ChronoUnit.DAYS)
            "1Y" -> Instant.now().minus(365, ChronoUnit.DAYS)
            else -> from
        }
        return candleRepository.findCandles(stockId, table, effectiveFrom, to)
    }
}
