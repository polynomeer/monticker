package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.infrastructure.BrokerageBalance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class PreviewBalanceCacheTest {

    private class MutableClock(var now: Instant = Instant.parse("2026-11-05T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock()
    private val cache = PreviewBalanceCache(Duration.ofSeconds(10), clock)
    private var loads = 0
    private fun load(): BrokerageBalance {
        loads++
        return BrokerageBalance(BigDecimal(loads), BigDecimal(loads), emptyList())
    }

    @Test
    fun `TTL 안에서는 증권사를 다시 부르지 않는다`() {
        cache.getOrLoad(1L, ::load)
        clock.now = clock.now.plusSeconds(9)
        assertThat(cache.getOrLoad(1L, ::load).cash).isEqualTo(BigDecimal(1))
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `TTL이 지나면 새로 부른다`() {
        cache.getOrLoad(1L, ::load)
        clock.now = clock.now.plusSeconds(10)
        assertThat(cache.getOrLoad(1L, ::load).cash).isEqualTo(BigDecimal(2))
    }

    @Test
    fun `계좌가 다르면 잔고를 나눠 쓰지 않는다`() {
        cache.getOrLoad(1L, ::load)
        assertThat(cache.getOrLoad(2L, ::load).cash).isEqualTo(BigDecimal(2))
    }

    @Test
    fun `조회 실패는 저장하지 않는다`() {
        assertThrows<IllegalStateException> { cache.getOrLoad(1L) { error("증권사 오류") } }
        assertThat(cache.getOrLoad(1L, ::load).cash).isEqualTo(BigDecimal(1))
    }
}
