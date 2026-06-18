package com.monticker.worker.marketdata

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.random.Random

data class GeneratedTick(
    val stockId: Long,
    val symbol: String,
    val market: String,
    val price: BigDecimal,
    val volume: Long,
    val tradeTime: Instant,
)

@Component
class MockPriceGenerator {

    private val basePrice = mapOf(
        "005930" to BigDecimal("71000"),
        "000660" to BigDecimal("180000"),
        "035420" to BigDecimal("210000"),
        "AAPL"   to BigDecimal("220"),
        "NVDA"   to BigDecimal("140"),
    )

    private val marketOf = mapOf(
        "005930" to "KOSPI",
        "000660" to "KOSPI",
        "035420" to "KOSPI",
        "AAPL"   to "NASDAQ",
        "NVDA"   to "NASDAQ",
    )

    private val currentPrice = basePrice.toMutableMap()

    fun generate(): List<GeneratedTick> {
        return currentPrice.keys.map { symbol ->
            val prev = currentPrice[symbol]!!
            val change = prev.multiply(BigDecimal(Random.nextDouble(-0.005, 0.005)))
                .setScale(0, RoundingMode.HALF_UP)
            val next = (prev + change).coerceAtLeast(BigDecimal.ONE)
            currentPrice[symbol] = next

            GeneratedTick(
                stockId = symbolToId(symbol),
                symbol = symbol,
                market = marketOf[symbol] ?: "UNKNOWN",
                price = next,
                volume = Random.nextLong(1000, 50000),
                tradeTime = Instant.now(),
            )
        }
    }

    private fun symbolToId(symbol: String): Long = when (symbol) {
        "005930" -> 1L
        "000660" -> 2L
        "035420" -> 3L
        "AAPL"   -> 4L
        "NVDA"   -> 5L
        else -> 0L
    }
}
