package com.monticker.api.marketdata.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.random.Random

@Service
class OrderBookService(private val jdbc: JdbcTemplate) {

    fun getOrderBook(stockId: Long): OrderBookResponse {
        val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", stockId)
        val currentPrice: BigDecimal = jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId
        ) ?: throw IllegalArgumentException("현재가 없음: stockId=$stockId")

        val unit = priceUnit(currentPrice)

        val asks = (1..10).map { i ->
            val price = roundToUnit(currentPrice.multiply(BigDecimal("1") + BigDecimal(i).multiply(BigDecimal("0.001"))), unit)
            val qty   = ((11 - i) * Random.nextLong(100, 2001))
            OrderBookLevel(price, qty, price.multiply(BigDecimal(qty)))
        }

        val bids = (1..10).map { i ->
            val price = roundToUnit(currentPrice.multiply(BigDecimal("1") - BigDecimal(i).multiply(BigDecimal("0.001"))), unit)
            val qty   = ((11 - i) * Random.nextLong(100, 2001))
            OrderBookLevel(price, qty, price.multiply(BigDecimal(qty)))
        }

        return OrderBookResponse(stockId, info["symbol"] as String, currentPrice, asks, bids, Instant.now())
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
}

data class OrderBookResponse(val stockId: Long, val symbol: String, val currentPrice: BigDecimal, val asks: List<OrderBookLevel>, val bids: List<OrderBookLevel>, val updatedAt: Instant)
data class OrderBookLevel(val price: BigDecimal, val quantity: Long, val amount: BigDecimal)
