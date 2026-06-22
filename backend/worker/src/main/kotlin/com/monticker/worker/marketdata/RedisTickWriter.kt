package com.monticker.worker.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisTickWriter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun write(tick: GeneratedTick) {
        val key = "stock:price:${tick.market}:${tick.symbol}"
        val value = objectMapper.writeValueAsString(
            mapOf(
                "stockId"   to tick.stockId,
                "symbol"    to tick.symbol,
                "price"     to tick.price,
                "volume"    to tick.volume,
                "tradeTime" to tick.tradeTime.toString(),
            )
        )
        redisTemplate.opsForValue().set(key, value)
    }
}
