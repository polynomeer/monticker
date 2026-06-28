package com.monticker.worker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * KIS H0STASP0 (실시간 호가) 파서 + Redis 저장
 *
 * pipe-delimited 필드 순서 (헤더 4개 이후):
 * idx 0  : MKSC_SHRN_ISCD  (종목코드)
 * idx 1  : BSOP_HOUR       (영업시간)
 * idx 2~11  : ASKP1~10     (매도호가 1~10)
 * idx 12~21 : BIDP1~10     (매수호가 1~10)
 * idx 22~31 : ASKP_RSQN1~10 (매도잔량)
 * idx 32~41 : BIDP_RSQN1~10 (매수잔량)
 * idx 42~43 : TOTAL_ASKP_RSQN, TOTAL_BIDP_RSQN
 */
@Component
class KisOrderBookHandler(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    // Redis TTL — 호가 데이터는 30초 이상 오래되면 신뢰 불가
    private val TTL = Duration.ofSeconds(30)

    fun handle(parts: List<String>) {
        // parts[0]=encType, [1]=trId, [2]=dataCount, [3..] = actual fields
        val fields = parts.drop(3)
        if (fields.size < 44) return

        val symbol = fields[0]
        val asks = (0 until 10).map { i ->
            OrderBookLevel(
                price    = fields[2 + i].toBigDecimalOrNull() ?: BigDecimal.ZERO,
                quantity = fields[22 + i].toLongOrNull() ?: 0L,
            )
        }.filter { it.price > BigDecimal.ZERO }

        val bids = (0 until 10).map { i ->
            OrderBookLevel(
                price    = fields[12 + i].toBigDecimalOrNull() ?: BigDecimal.ZERO,
                quantity = fields[32 + i].toLongOrNull() ?: 0L,
            )
        }.filter { it.price > BigDecimal.ZERO }

        val payload = mapper.writeValueAsString(
            mapOf(
                "symbol"    to symbol,
                "asks"      to asks,
                "bids"      to bids,
                "updatedAt" to Instant.now().toString(),
                "source"    to "KIS_REALTIME",
            )
        )

        val key = "orderbook:$symbol"
        redisTemplate.opsForValue().set(key, payload, TTL)
        log.debug("OrderBook cached: {} asks={} bids={}", symbol, asks.size, bids.size)
    }
}

data class OrderBookLevel(val price: BigDecimal, val quantity: Long)
