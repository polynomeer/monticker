package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.StockPriceProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class RedisPriceCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : StockPriceProvider {

    companion object {
        fun priceKey(market: String, symbol: String) = "stock:price:$market:$symbol"
    }

    override fun getLatestPrice(stockId: Long, symbol: String): PriceTick? {
        val key = "stock:price:*:$symbol"
        val keys = redisTemplate.keys(key)
        val rawKey = keys?.firstOrNull() ?: return null
        val json = redisTemplate.opsForValue().get(rawKey) ?: return null
        return try {
            val map = objectMapper.readValue(json, Map::class.java)
            PriceTick(
                stockId = (map["stockId"] as Number).toLong(),
                symbol = map["symbol"] as String,
                price = BigDecimal(map["price"].toString()),
                volume = (map["volume"] as Number).toLong(),
                tradeTime = Instant.parse(map["tradeTime"] as String),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun set(market: String, tick: PriceTick) {
        val key = priceKey(market, tick.symbol)
        val json = objectMapper.writeValueAsString(
            mapOf(
                "stockId" to tick.stockId,
                "symbol" to tick.symbol,
                "price" to tick.price,
                "volume" to tick.volume,
                "tradeTime" to tick.tradeTime.toString(),
            )
        )
        redisTemplate.opsForValue().set(key, json)
    }
}
