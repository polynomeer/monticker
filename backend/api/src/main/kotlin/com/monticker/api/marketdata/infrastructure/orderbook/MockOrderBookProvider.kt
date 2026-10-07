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
        // 0.1% 간격이 호가 단위보다 좁으면 반올림 결과가 겹친다 — 한 단계는 최소 한 호가 단위 떨어뜨린다
        var lastAsk: BigDecimal? = null
        val asks = (1..10).map { i ->
            val target = roundToUnit(refPrice * (BigDecimal.ONE + BigDecimal(i) * BigDecimal("0.001")), unit)
            val p = lastAsk?.let { maxOf(target, it + unit) } ?: target
            lastAsk = p
            OrderLevel(p, (11 - i) * Random.nextLong(100, 2001))
        }
        var lastBid: BigDecimal? = null
        val bids = (1..10).map { i ->
            val target = roundToUnit(refPrice * (BigDecimal.ONE - BigDecimal(i) * BigDecimal("0.001")), unit)
            val p = lastBid?.let { minOf(target, it - unit) } ?: target
            lastBid = p
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
