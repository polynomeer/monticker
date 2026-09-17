package com.monticker.worker.experiment

import com.monticker.worker.marketdata.GeneratedTick
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class TickOrderMonitorTest {
    private fun monitor(hot: Long = 0) =
        TickOrderMonitor(SimpleMeterRegistry(), hot, 0, 0, false, "t", 500, mockk<StringRedisTemplate>(relaxed = true))

    private fun tick(stock: Long, seq: Long?) = GeneratedTick(
        stockId = stock, symbol = "S$stock", market = "KOSPI", price = BigDecimal.ONE, volume = 1,
        tradeTime = Instant.now(), generatedAt = Instant.now().minusMillis(5), seq = seq,
    )

    @Test
    fun `in-order sequence counts nothing`() {
        val m = monitor()
        (1L..100L).forEach { m.observe(0, tick(1, it)) }
        val s = m.snapshot()
        assertEquals(0L, s["violations"]); assertEquals(0L, s["dups"]); assertEquals(0L, s["gaps"])
        assertEquals(100L, s["ticks_with_seq"])
    }

    @Test
    fun `reorder, redelivery and gap are told apart`() {
        val m = monitor()
        listOf(1L, 2L, 4L, 3L, 3L, 8L).forEach { m.observe(0, tick(1, it)) }
        // 4 before 3 → gap 1 (seq 3 missing), then 3 arrives late → violation, 3 again → dup, 8 → gap 3 (5,6,7)
        val s = m.snapshot()
        assertEquals(1L, s["violations"]); assertEquals(1L, s["dups"]); assertEquals(4L, s["gaps"])
    }

    @Test
    fun `stocks are independent and ticks without seq only count e2e`() {
        val m = monitor(hot = 7)
        m.observe(2, tick(7, 1)); m.observe(2, tick(9, 1)); m.observe(5, tick(11, null))
        val s = m.snapshot()
        assertEquals(3L, s["ticks"]); assertEquals(2L, s["ticks_with_seq"]); assertEquals(0L, s["violations"])
        @Suppress("UNCHECKED_CAST") val e2e = s["e2e"] as Map<String, Map<String, Any?>>
        assertEquals(1, e2e["hot"]!!["n"]); assertEquals(1, e2e["same_partition_as_hot"]!!["n"]); assertEquals(1, e2e["other"]!!["n"])
    }
}
