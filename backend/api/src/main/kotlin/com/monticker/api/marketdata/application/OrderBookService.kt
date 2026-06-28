package com.monticker.api.marketdata.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.random.Random

@Service
class OrderBookService(
    private val jdbc: JdbcTemplate,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    fun getOrderBook(stockId: Long): OrderBookResponse {
        val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", stockId)
        val symbol = info["symbol"] as String

        val currentPrice: BigDecimal = jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId
        ) ?: throw IllegalArgumentException("현재가 없음: stockId=$stockId")

        // Redis에 실시간 호가 있으면 사용, 없으면 Mock
        val cached = redisTemplate.opsForValue().get("orderbook:$symbol")
        if (cached != null) {
            return parseRedisOrderBook(stockId, symbol, currentPrice, cached)
        }

        log.debug("No real-time order book for {} — using mock", symbol)
        return buildMockOrderBook(stockId, symbol, currentPrice)
    }

    private fun parseRedisOrderBook(
        stockId: Long,
        symbol: String,
        currentPrice: BigDecimal,
        json: String,
    ): OrderBookResponse {
        val root = mapper.readTree(json)
        val asks = root["asks"].map { node ->
            val price = node["price"].decimalValue()
            val qty   = node["quantity"].longValue()
            OrderBookLevel(price, qty, price.multiply(BigDecimal(qty)))
        }
        val bids = root["bids"].map { node ->
            val price = node["price"].decimalValue()
            val qty   = node["quantity"].longValue()
            OrderBookLevel(price, qty, price.multiply(BigDecimal(qty)))
        }
        val updatedAt = root["updatedAt"]?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.now()

        return OrderBookResponse(stockId, symbol, currentPrice, asks, bids, updatedAt, source = "KIS_REALTIME")
    }

    private fun buildMockOrderBook(
        stockId: Long,
        symbol: String,
        currentPrice: BigDecimal,
    ): OrderBookResponse {
        val unit = priceUnit(currentPrice)
        val asks = (1..10).map { i ->
            val price = roundToUnit(currentPrice * (BigDecimal.ONE + BigDecimal(i) * BigDecimal("0.001")), unit)
            val qty   = (11 - i) * Random.nextLong(100, 2001)
            OrderBookLevel(price, qty, price * BigDecimal(qty))
        }
        val bids = (1..10).map { i ->
            val price = roundToUnit(currentPrice * (BigDecimal.ONE - BigDecimal(i) * BigDecimal("0.001")), unit)
            val qty   = (11 - i) * Random.nextLong(100, 2001)
            OrderBookLevel(price, qty, price * BigDecimal(qty))
        }
        return OrderBookResponse(stockId, symbol, currentPrice, asks, bids, Instant.now(), source = "MOCK")
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

data class OrderBookResponse(
    val stockId: Long,
    val symbol: String,
    val currentPrice: BigDecimal,
    val asks: List<OrderBookLevel>,
    val bids: List<OrderBookLevel>,
    val updatedAt: Instant,
    val source: String = "MOCK",
)

data class OrderBookLevel(val price: BigDecimal, val quantity: Long, val amount: BigDecimal)
