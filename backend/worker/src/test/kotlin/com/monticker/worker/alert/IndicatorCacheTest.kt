package com.monticker.worker.alert

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/** ADR-044 — 지표는 TTL 안에서 한 번만 계산한다. VOLUME_SURGE 20일 집계가 틱마다 돌던 경로가 여기서 끊긴다. */
class IndicatorCacheTest {

    @Test
    fun `TTL 안의 반복 조회는 DB 를 한 번만 친다`() {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.queryForMap(any<String>(), *anyVararg()) } returns mapOf("today_vol" to 5000L, "avg_vol" to 1000.0)
        var now = 0L
        val cache = IndicatorCache(jdbc, ttlMs = 60_000, clock = { now })

        repeat(1000) { cache.volumeStats(5, 20) }
        verify(exactly = 1) { jdbc.queryForMap(any<String>(), *anyVararg()) }
        assertThat(cache.volumeStats(5, 20)).isEqualTo(5000L to 1000.0)

        now = 61_000                                     // TTL 경과 → 재계산
        cache.volumeStats(5, 20)
        verify(exactly = 2) { jdbc.queryForMap(any<String>(), *anyVararg()) }
    }

    @Test
    fun `종목과 period 가 다르면 별도 항목이다`() {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.queryForObject(any<String>(), Double::class.java, *anyVararg()) } returns 100.0
        val cache = IndicatorCache(jdbc, ttlMs = 60_000, clock = { 0L })
        cache.movingAverage(5, 20); cache.movingAverage(5, 60); cache.movingAverage(6, 20)
        verify(exactly = 3) { jdbc.queryForObject(any<String>(), Double::class.java, *anyVararg()) }
        assertThat(cache.size()).isEqualTo(3)
    }
}
