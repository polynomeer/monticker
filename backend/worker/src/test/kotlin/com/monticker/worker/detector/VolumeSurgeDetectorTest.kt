package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Instant

class VolumeSurgeDetectorTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val writer = mockk<StockEventWriter>(relaxed = true)
    private val ops = mockk<ValueOperations<String, String>>()
    private val detector = VolumeSurgeDetector(redisTemplate, writer)

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns ops
    }

    @Test
    fun `initializes EMA on first tick and does not write event`() {
        every { ops.get(any()) } returns null
        every { ops.set(any(), any()) } just Runs

        detector.detect(makeTick(volume = 10_000))

        verify(exactly = 0) { writer.write(any()) }
    }

    @Test
    fun `writes VOLUME_SURGE event with strong-signal score when ratio reaches 5x`() {
        every { ops.get(match<String> { it.contains("ema") }) } returns "3000.0"
        every { ops.set(any(), any()) } just Runs
        every { writer.write(any()) } returns true

        detector.detect(makeTick(volume = 15_000)) // 5× EMA

        verify(exactly = 1) {
            writer.write(match { it.eventType == DetectedEventType.VOLUME_SURGE && it.importanceScore == 85 })
        }
    }

    @Test
    fun `writes VOLUME_SURGE exactly at the 3x ratio boundary with meaningful-signal score`() {
        // ema=1000.0, volume=3000 → ratio=3000.0/1000.0=3.0 정확히 (경계값, 미만이 아니므로 발동)
        every { ops.get(match<String> { it.contains("ema") }) } returns "1000.0"
        every { ops.set(any(), any()) } just Runs
        every { writer.write(any()) } returns true

        detector.detect(makeTick(volume = 3_000))

        verify(exactly = 1) {
            writer.write(match { it.eventType == DetectedEventType.VOLUME_SURGE && it.importanceScore == 60 })
        }
    }

    @Test
    fun `does not write when ratio is just under the 3x boundary`() {
        // ema=1000.0, volume=2999 → ratio=2.999 < 3.0 → 미발동
        every { ops.get(match<String> { it.contains("ema") }) } returns "1000.0"
        every { ops.set(any(), any()) } just Runs

        detector.detect(makeTick(volume = 2_999))

        verify(exactly = 0) { writer.write(any()) }
    }

    @Test
    fun `does not write event when ratio is well below 3x`() {
        every { ops.get(match<String> { it.contains("ema") }) } returns "10000.0"
        every { ops.set(any(), any()) } just Runs

        detector.detect(makeTick(volume = 15_000)) // 1.5× EMA

        verify(exactly = 0) { writer.write(any()) }
    }

    private fun makeTick(volume: Long) = GeneratedTick(
        stockId = 1L, symbol = "005930", market = "KOSPI",
        price = BigDecimal("71000"), volume = volume, tradeTime = Instant.now(),
    )
}
