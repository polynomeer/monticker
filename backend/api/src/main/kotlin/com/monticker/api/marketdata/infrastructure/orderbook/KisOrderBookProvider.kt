package com.monticker.api.marketdata.infrastructure.orderbook

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS WebSocket(H0STASP0)이 worker에서 Redis에 캐시한 실시간 호가를 읽는다.
 * Redis 키: orderbook:{symbol}  TTL: 30s
 */
@Component
class KisOrderBookProvider(
    private val redisTemplate: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : OrderBookProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getOrderBook(symbol: String, market: String, refPrice: BigDecimal): OrderBookSnapshot? {
        val json = redisTemplate.opsForValue().get("orderbook:$symbol") ?: return null
        return try {
            val root = mapper.readTree(json)
            OrderBookSnapshot(
                asks = root["asks"].map { n ->
                    OrderLevel(n["price"].decimalValue(), n["quantity"].longValue())
                },
                bids = root["bids"].map { n ->
                    OrderLevel(n["price"].decimalValue(), n["quantity"].longValue())
                },
                updatedAt = root["updatedAt"]?.asText()
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: Instant.now(),
                source = DataSource.KIS_REALTIME,
            )
        } catch (e: Exception) {
            log.warn("KIS Redis parse error for {}: {}", symbol, e.message)
            null
        }
    }
}
