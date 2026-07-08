package com.monticker.api.analytics.application

import com.monticker.api.analytics.infrastructure.RegimeHistoryRepository
import com.monticker.api.backtest.domain.DailyCandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.LocalDate

class RegimeDetectorServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val regimeHistoryRepository = mockk<RegimeHistoryRepository>()
    private val queryService = RegimeDetectorQueryService(jdbc)
    private val service = RegimeDetectorService(queryService, regimeHistoryRepository)

    private fun candle(day: Int, close: Double, high: Double = close, low: Double = close) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal.valueOf(close), high = BigDecimal.valueOf(high),
        low = BigDecimal.valueOf(low), close = BigDecimal.valueOf(close), volume = 1000L,
    )

    // ── calculateADX ─────────────────────────────────────────────────────────────

    @Test
    fun `calculateADX returns zero when fewer than 2x the period candles are available`() {
        val candles = (0..20).map { candle(it, 100.0) }
        assertThat(queryService.calculateADX(candles, period = 14)).isEqualTo(0.0)
    }

    @Test
    fun `calculateADX is low for a flat, directionless price series`() {
        val candles = (0..60).map { candle(it, 100.0, high = 100.5, low = 99.5) }
        val adx = queryService.calculateADX(candles, period = 14)
        assertThat(adx).isLessThan(20.0)
    }

    @Test
    fun `calculateADX is elevated for a consistently trending price series`() {
        val candles = (0..60).map { i -> candle(i, 100.0 + i * 2, high = 100.0 + i * 2 + 1, low = 100.0 + i * 2 - 1) }
        val adx = queryService.calculateADX(candles, period = 14)
        assertThat(adx).isGreaterThan(20.0)
    }

    // ── calculateVolatility ──────────────────────────────────────────────────────

    @Test
    fun `calculateVolatility is zero for a perfectly flat price series`() {
        val candles = (0..25).map { candle(it, 100.0) }
        assertThat(queryService.calculateVolatility(candles, period = 20)).isEqualTo(0.0)
    }

    @Test
    fun `calculateVolatility increases with larger day-to-day price swings`() {
        val calmCandles = (0..25).map { candle(it, 100.0 + (it % 2)) }
        val volatileCandles = (0..25).map { candle(it, if (it % 2 == 0) 80.0 else 120.0) }

        val calmVol = queryService.calculateVolatility(calmCandles, period = 20)
        val volatileVol = queryService.calculateVolatility(volatileCandles, period = 20)

        assertThat(volatileVol).isGreaterThan(calmVol)
    }

    @Test
    fun `calculateVolatility returns zero when fewer than 2 candles are available`() {
        assertThat(queryService.calculateVolatility(listOf(candle(0, 100.0)), period = 20)).isEqualTo(0.0)
    }

    // ── calculateTrendSlope ──────────────────────────────────────────────────────

    @Test
    fun `calculateTrendSlope is positive for a rising price series`() {
        val candles = (0..60).map { candle(it, 100.0 + it) }
        assertThat(queryService.calculateTrendSlope(candles, period = 60)).isGreaterThan(0.0)
    }

    @Test
    fun `calculateTrendSlope is negative for a falling price series`() {
        val candles = (0..60).map { candle(it, 200.0 - it) }
        assertThat(queryService.calculateTrendSlope(candles, period = 60)).isLessThan(0.0)
    }

    @Test
    fun `calculateTrendSlope is approximately zero for a flat price series`() {
        val candles = (0..60).map { candle(it, 100.0) }
        assertThat(queryService.calculateTrendSlope(candles, period = 60)).isCloseTo(0.0, within(0.0001))
    }

    @Test
    fun `calculateTrendSlope returns zero when fewer than 2 candles are available`() {
        assertThat(queryService.calculateTrendSlope(listOf(candle(0, 100.0)), period = 60)).isEqualTo(0.0)
    }

    // ── classify ─────────────────────────────────────────────────────────────────

    @Test
    fun `classify returns HIGH_VOL when volatility exceeds the 80th percentile threshold`() {
        assertThat(queryService.classify(adx = 30.0, volatility = 0.5, volatilityPercentile80 = 0.3, slope = 0.01)).isEqualTo("HIGH_VOL")
    }

    @Test
    fun `classify returns SIDEWAYS when ADX is below 20 and volatility is in range`() {
        assertThat(queryService.classify(adx = 15.0, volatility = 0.1, volatilityPercentile80 = 0.3, slope = 0.01)).isEqualTo("SIDEWAYS")
    }

    @Test
    fun `classify returns BULL when ADX is at or above 20 with a positive slope`() {
        assertThat(queryService.classify(adx = 25.0, volatility = 0.1, volatilityPercentile80 = 0.3, slope = 0.01)).isEqualTo("BULL")
    }

    @Test
    fun `classify returns BEAR when ADX is at or above 20 with a non-positive slope`() {
        assertThat(queryService.classify(adx = 25.0, volatility = 0.1, volatilityPercentile80 = 0.3, slope = -0.01)).isEqualTo("BEAR")
    }

    // ── classifyRegime (integration) ─────────────────────────────────────────────

    @Test
    fun `classifyRegime returns UNKNOWN with an error when there is insufficient candle history`() {
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), any(), any(), any())
        } returns (0..10).map { candle(it, 100.0) }

        val result = service.classifyRegime(stockId = 1L)

        assertThat(result.regime).isEqualTo("UNKNOWN")
        assertThat(result.error).isNotNull()
    }

    @Test
    fun `classifyRegime saves a new RegimeHistory row when none exists for today`() {
        val candles = (0..60).map { candle(it, 100.0 + it) }
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), any(), any(), any())
        } returns candles
        every { regimeHistoryRepository.findByStockIdAndRegimeDate(1L, candles.last().date) } returns null
        every { regimeHistoryRepository.save(any()) } answers { firstArg() }

        service.classifyRegime(stockId = 1L)

        verify(exactly = 1) { regimeHistoryRepository.save(any()) }
    }

    @Test
    fun `classifyRegime does not duplicate a RegimeHistory row already recorded for today`() {
        val candles = (0..60).map { candle(it, 100.0 + it) }
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), any(), any(), any())
        } returns candles
        val existing = com.monticker.api.analytics.domain.RegimeHistory(
            stockId = 1L, regimeDate = candles.last().date, regime = "BULL",
        )
        every { regimeHistoryRepository.findByStockIdAndRegimeDate(1L, candles.last().date) } returns existing

        service.classifyRegime(stockId = 1L)

        verify(exactly = 0) { regimeHistoryRepository.save(any()) }
    }

    @Test
    fun `classifyMarketRegime returns UNKNOWN when no representative stock exists for the market`() {
        every {
            jdbc.queryForList("SELECT id FROM stocks WHERE market = ? ORDER BY id LIMIT 1", Long::class.java, "KOSPI")
        } returns emptyList()

        val result = service.classifyMarketRegime("KOSPI")

        assertThat(result.regime).isEqualTo("UNKNOWN")
        assertThat(result.error).isNotNull()
    }

    @Test
    fun `classifyMarketRegime uses the first matching stock as a representative proxy`() {
        every {
            jdbc.queryForList("SELECT id FROM stocks WHERE market = ? ORDER BY id LIMIT 1", Long::class.java, "KOSPI")
        } returns listOf(2L)
        val candles = (0..60).map { candle(it, 100.0 + it) }
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), eq(2L), any(), any())
        } returns candles
        every { regimeHistoryRepository.findByMarketAndRegimeDate("KOSPI", candles.last().date) } returns null
        every { regimeHistoryRepository.save(any()) } answers { firstArg() }

        val result = service.classifyMarketRegime("KOSPI")

        assertThat(result.error).isNull()
        assertThat(result.regime).isIn("BULL", "BEAR", "SIDEWAYS", "HIGH_VOL")
    }
}
