package com.monticker.api.marketdata

import com.monticker.api.marketdata.application.CandleService
import com.monticker.api.marketdata.infrastructure.CandleRepository
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 관심종목 52주 최고/최저 — candles_1d에서 KST 날짜 구간으로 일괄 집계한다. 경계는 KST 자정을 timestamptz로 바꿔
 * 비교하므로 JVM·DB 세션 타임존과 무관해야 한다(`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 돌린다).
 */
class PriceRange52wIntegrationTest : PostgresIntegrationTest() {

    private val kst = ZoneId.of("Asia/Seoul")
    private val service by lazy { CandleService(CandleRepository(jdbcTemplate)) }
    private val today = LocalDate.of(2026, 10, 8)
    private val windowStart = LocalDate.of(2025, 10, 9)   // today - 52주

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '52주테스트', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "HL" + UUID.randomUUID().toString().take(8),
    )!!

    /** worker CandleAggregator처럼 candle_time = 그 날짜의 KST 자정 */
    private fun day(stockId: Long, date: LocalDate, high: Int, low: Int) {
        jdbcTemplate.update(
            "INSERT INTO candles_1d (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 100, ?)",
            stockId, low, high, low, low, OffsetDateTime.ofInstant(date.atStartOfDay(kst).toInstant(), kst),
        )
    }

    @Test
    fun `window includes KST window start and today but not the day before or tomorrow`() {
        val a = stock()
        day(a, windowStart.minusDays(1), high = 9_999, low = 1)     // 구간 전날 — 빠진다
        day(a, windowStart, high = 150, low = 90)                    // 구간 첫날 KST 자정 — 들어간다
        day(a, LocalDate.of(2026, 3, 3), high = 140, low = 70)
        day(a, today, high = 120, low = 100)                         // 오늘(장중) — 들어간다
        day(a, today.plusDays(1), high = 5_000, low = 2)             // 내일 — 빠진다

        val r = service.get52WeekRanges(listOf(a), today)[a]!!

        assertThat(r.high).isEqualByComparingTo(BigDecimal(150))
        assertThat(r.low).isEqualByComparingTo(BigDecimal(70))
        assertThat(r.firstDate).isEqualTo(windowStart)
        assertThat(r.lastDate).isEqualTo(today)
        assertThat(r.tradingDays).isEqualTo(3)
        assertThat(r.fullPeriod).isTrue()
    }

    @Test
    fun `short history returns the high and low of what exists and its covered period`() {
        val b = stock()
        day(b, LocalDate.of(2026, 7, 1), high = 55, low = 41)
        day(b, LocalDate.of(2026, 10, 7), high = 60, low = 50)

        val r = service.get52WeekRanges(listOf(b), today)[b]!!

        assertThat(r.high).isEqualByComparingTo(BigDecimal(60))
        assertThat(r.low).isEqualByComparingTo(BigDecimal(41))
        assertThat(r.firstDate).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(r.lastDate).isEqualTo(LocalDate.of(2026, 10, 7))
        assertThat(r.fullPeriod).isFalse()
    }

    @Test
    fun `many stocks in one call and stocks without candles are absent`() {
        val withData = (1..5).map { stock().also { id -> day(id, today.minusDays(3), high = 10 + id.toInt() % 7, low = 5) } }
        val empty = stock()

        val result = service.get52WeekRanges(withData + empty, today)

        assertThat(result.keys).containsExactlyInAnyOrderElementsOf(withData)
        assertThat(result).doesNotContainKey(empty)
    }
}
