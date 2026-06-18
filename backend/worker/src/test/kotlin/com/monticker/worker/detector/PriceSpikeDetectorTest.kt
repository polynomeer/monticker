package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Instant

class PriceSpikeDetectorTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val writer = mockk<StockEventWriter>(relaxed = true)
    private val ops = mockk<ValueOperations<String, String>>()
    private val detector = PriceSpikeDetector(redisTemplate, writer)

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns ops
    }

    @Test
    fun `initializes on first tick and does not write event`() {
        every { ops.get(any()) } returns null
        every { ops.set(any(), any()) } just Runs

        detector.detect(makeTick(BigDecimal("71000")))

        verify(exactly = 0) { writer.write(any()) }
    }

    @Test
    fun `writes PRICE_SPIKE event on large upward movement`() {
        every { ops.get(match<String> { it.contains("prev") }) } returns "70000"
        every { ops.get(match<String> { it.contains("ema") }) } returns "0.01" // tiny EMA → large ratio
        every { ops.set(any(), any()) } just Runs
        every { writer.write(any()) } returns true

        detector.detect(makeTick(BigDecimal("71500"))) // +2.14%

        verify(exactly = 1) { writer.write(match { it.eventType == DetectedEventType.PRICE_SPIKE }) }
    }

    @Test
    fun `writes PRICE_DROP event on large downward movement`() {
        every { ops.get(match<String> { it.contains("prev") }) } returns "71000"
        every { ops.get(match<String> { it.contains("ema") }) } returns "0.01"
        every { ops.set(any(), any()) } just Runs
        every { writer.write(any()) } returns true

        detector.detect(makeTick(BigDecimal("69500"))) // -2.11%

        verify(exactly = 1) { writer.write(match { it.eventType == DetectedEventType.PRICE_DROP }) }
    }

    private fun makeTick(price: BigDecimal) = GeneratedTick(
        stockId = 1L, symbol = "005930", market = "KOSPI",
        price = price, volume = 10_000L, tradeTime = Instant.now(),
    )
}
