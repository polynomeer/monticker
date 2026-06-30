package com.monticker.api.marketdata.infrastructure.orderbook

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal

class KisOrderBookProviderTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val mapper = ObjectMapper()
    private val provider = KisOrderBookProvider(redisTemplate, mapper)

    private fun stubRedis(symbol: String, json: String?) {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get("orderbook:$symbol") } returns json
    }

    @Test
    fun `returns null when no cached orderbook exists in Redis`() {
        stubRedis("005930", null)

        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result).isNull()
    }

    @Test
    fun `parses a cached orderbook JSON into asks bids and source KIS_REALTIME`() {
        val json = """
            {
              "asks": [{"price": 70100, "quantity": 50}, {"price": 70200, "quantity": 30}],
              "bids": [{"price": 70000, "quantity": 40}, {"price": 69900, "quantity": 20}],
              "updatedAt": "2026-06-30T10:00:00Z"
            }
        """.trimIndent()
        stubRedis("005930", json)

        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result).isNotNull()
        assertThat(result!!.source).isEqualTo(DataSource.KIS_REALTIME)
        assertThat(result.asks).hasSize(2)
        assertThat(result.asks[0].price).isEqualByComparingTo(BigDecimal("70100"))
        assertThat(result.asks[0].quantity).isEqualTo(50L)
        assertThat(result.bids).hasSize(2)
        assertThat(result.bids[0].price).isEqualByComparingTo(BigDecimal("70000"))
    }

    @Test
    fun `falls back to the current time when updatedAt is missing or unparsable`() {
        val json = """{"asks": [], "bids": []}"""
        stubRedis("005930", json)

        val before = java.time.Instant.now()
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))
        val after = java.time.Instant.now()

        assertThat(result).isNotNull()
        assertThat(result!!.updatedAt).isBetween(before, after)
    }

    @Test
    fun `returns null and does not throw when the cached JSON is malformed`() {
        stubRedis("005930", "{not valid json")

        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result).isNull()
    }

    @Test
    fun `returns null when the cached JSON is missing the asks or bids fields`() {
        stubRedis("005930", """{"bids": []}""")

        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result).isNull()
    }

    @Test
    fun `looks up the cache using the orderbook colon symbol key format`() {
        stubRedis("AAPL", null)

        provider.getOrderBook("AAPL", "NASDAQ", BigDecimal("220"))

        io.mockk.verify { valueOps.get("orderbook:AAPL") }
    }
}
