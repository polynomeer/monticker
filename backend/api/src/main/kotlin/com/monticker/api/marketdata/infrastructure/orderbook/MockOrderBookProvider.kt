package com.monticker.api.marketdata.infrastructure.orderbook

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.random.Random

@Component
class MockOrderBookProvider : OrderBookProvider {

    override fun getOrderBook(symbol: String, market: String, refPrice: BigDecimal): OrderBookSnapshot {
        val unit = priceUnit(refPrice)
        val asks = (1..10).map { i ->
            val p = roundToUnit(refPrice * (BigDecimal.ONE + BigDecimal(i) * BigDecimal("0.001")), unit)
            OrderLevel(p, (11 - i) * Random.nextLong(100, 2001))
        }
        val bids = (1..10).map { i ->
            val p = roundToUnit(refPrice * (BigDecimal.ONE - BigDecimal(i) * BigDecimal("0.001")), unit)
            OrderLevel(p, (11 - i) * Random.nextLong(100, 2001))
        }
        return OrderBookSnapshot(asks, bids, Instant.now(), DataSource.MOCK)
    }

    private fun priceUnit(price: BigDecimal): BigDecimal = when {
        price >= BigDecimal("500000") -> BigDecimal("1000")
        price >= BigDecimal("100000") -> BigDecimal("500")
        price >= BigDecimal("50000")  -> BigDecimal("100")
        price >= BigDecimal("10000")  -> BigDecimal("50")
        price >= BigDecimal("5000")   -> BigDecimal("10")
        price >= BigDecimal("1000")   -> BigDecimal("5")
        price >= BigDecimal("500")    -> BigDecimal("1")
        else                          -> BigDecimal("0.1")
    }

    private fun roundToUnit(price: BigDecimal, unit: BigDecimal): BigDecimal =
        price.divide(unit, 0, RoundingMode.HALF_UP).multiply(unit)

    private operator fun BigDecimal.times(other: BigDecimal) = this.multiply(other)
}
