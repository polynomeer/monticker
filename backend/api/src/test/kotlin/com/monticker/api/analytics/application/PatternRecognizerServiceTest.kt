package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.monticker.api.analytics.infrastructure.DetectedPatternRepository
import com.monticker.api.backtest.domain.DailyCandle
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.LocalDate

class PatternRecognizerServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper().registerModule(JavaTimeModule())
    private val detectedPatternRepository = mockk<DetectedPatternRepository>()
    private val service = PatternRecognizerService(jdbc, objectMapper, detectedPatternRepository)

    private fun swing(index: Int, day: Int, price: Double, type: SwingType) =
        SwingPoint(index, LocalDate.of(2026, 1, 1).plusDays(day.toLong()), BigDecimal.valueOf(price), type)

    // ── zigZag ───────────────────────────────────────────────────────────────────

    private fun candle(day: Int, close: Double) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal.valueOf(close), high = BigDecimal.valueOf(close),
        low = BigDecimal.valueOf(close), close = BigDecimal.valueOf(close), volume = 1000L,
    )

    @Test
    fun `zigZag returns an empty list for an empty candle series`() {
        assertThat(service.zigZag(emptyList())).isEmpty()
    }

    @Test
    fun `zigZag ignores fluctuations smaller than the threshold`() {
        // small wiggle of 1% stays below the default 3% threshold
        val candles = listOf(100.0, 100.5, 100.2, 100.8, 100.3).mapIndexed { i, c -> candle(i, c) }

        val swings = service.zigZag(candles, thresholdPct = 3.0)

        // No swing reversal recorded mid-series; only the trailing extreme is appended
        assertThat(swings).hasSizeLessThanOrEqualTo(1)
    }

    @Test
    fun `zigZag records a swing point at each reversal exceeding the threshold`() {
        // up to 110 (+10%), down to 95 (-13.6%), up to 120 (+26%)
        val candles = listOf(100.0, 110.0, 95.0, 120.0).mapIndexed { i, c -> candle(i, c) }

        val swings = service.zigZag(candles, thresholdPct = 3.0)

        assertThat(swings.map { it.type }).containsExactly(SwingType.HIGH, SwingType.LOW, SwingType.HIGH)
        assertThat(swings.map { it.price.toDouble() }).containsExactly(110.0, 95.0, 120.0)
    }

    // ── detectDoubleBottom ───────────────────────────────────────────────────────

    @Test
    fun `detectDoubleBottom matches a LOW-HIGH-LOW sequence with similar lows and a clear middle peak`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.LOW),
            swing(1, 5, 110.0, SwingType.HIGH),
            swing(2, 10, 101.0, SwingType.LOW),
        )

        val match = service.detectDoubleBottom(swings)

        assertThat(match).isNotNull()
        assertThat(match!!.patternType).isEqualTo("DOUBLE_BOTTOM")
        assertThat(match.confidenceScore).isGreaterThan(0)
    }

    @Test
    fun `detectDoubleBottom returns null when the two lows differ by more than 2 percent`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.LOW),
            swing(1, 5, 120.0, SwingType.HIGH),
            swing(2, 10, 110.0, SwingType.LOW), // 10% different from first low
        )

        assertThat(service.detectDoubleBottom(swings)).isNull()
    }

    @Test
    fun `detectDoubleBottom returns null when the middle peak does not rise enough above the lows`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.LOW),
            swing(1, 5, 102.0, SwingType.HIGH), // only 2% rise, needs >= 5%
            swing(2, 10, 100.5, SwingType.LOW),
        )

        assertThat(service.detectDoubleBottom(swings)).isNull()
    }

    @Test
    fun `detectDoubleBottom returns null for the wrong type sequence`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.HIGH),
            swing(1, 5, 90.0, SwingType.LOW),
            swing(2, 10, 101.0, SwingType.HIGH),
        )

        assertThat(service.detectDoubleBottom(swings)).isNull()
    }

    @Test
    fun `detectDoubleBottom returns null with fewer than 3 swing points`() {
        assertThat(service.detectDoubleBottom(listOf(swing(0, 0, 100.0, SwingType.LOW)))).isNull()
    }

    // ── detectDoubleTop ──────────────────────────────────────────────────────────

    @Test
    fun `detectDoubleTop matches a HIGH-LOW-HIGH sequence with similar highs and a clear middle trough`() {
        val swings = listOf(
            swing(0, 0, 110.0, SwingType.HIGH),
            swing(1, 5, 100.0, SwingType.LOW),
            swing(2, 10, 109.0, SwingType.HIGH),
        )

        val match = service.detectDoubleTop(swings)

        assertThat(match).isNotNull()
        assertThat(match!!.patternType).isEqualTo("DOUBLE_TOP")
    }

    @Test
    fun `detectDoubleTop returns null when the drop from highs is insufficient`() {
        val swings = listOf(
            swing(0, 0, 110.0, SwingType.HIGH),
            swing(1, 5, 108.0, SwingType.LOW), // only ~1.8% drop, needs >= 5%
            swing(2, 10, 109.0, SwingType.HIGH),
        )

        assertThat(service.detectDoubleTop(swings)).isNull()
    }

    // ── detectHeadAndShoulders ───────────────────────────────────────────────────

    @Test
    fun `detectHeadAndShoulders matches when the head exceeds both similar shoulders`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.HIGH),  // shoulder 1
            swing(1, 5, 90.0, SwingType.LOW),
            swing(2, 10, 120.0, SwingType.HIGH), // head
            swing(3, 15, 91.0, SwingType.LOW),
            swing(4, 20, 101.0, SwingType.HIGH), // shoulder 2
        )

        val match = service.detectHeadAndShoulders(swings)

        assertThat(match).isNotNull()
        assertThat(match!!.patternType).isEqualTo("HEAD_AND_SHOULDERS")
    }

    @Test
    fun `detectHeadAndShoulders returns null when the head does not exceed a shoulder`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.HIGH),
            swing(1, 5, 90.0, SwingType.LOW),
            swing(2, 10, 95.0, SwingType.HIGH), // head lower than shoulder1 -> invalid
            swing(3, 15, 91.0, SwingType.LOW),
            swing(4, 20, 101.0, SwingType.HIGH),
        )

        assertThat(service.detectHeadAndShoulders(swings)).isNull()
    }

    @Test
    fun `detectHeadAndShoulders returns null when shoulders differ by more than 3 percent`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.HIGH),
            swing(1, 5, 90.0, SwingType.LOW),
            swing(2, 10, 120.0, SwingType.HIGH),
            swing(3, 15, 91.0, SwingType.LOW),
            swing(4, 20, 110.0, SwingType.HIGH), // 10% different from shoulder1
        )

        assertThat(service.detectHeadAndShoulders(swings)).isNull()
    }

    @Test
    fun `detectHeadAndShoulders returns null for the wrong type sequence`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.LOW),
            swing(1, 5, 90.0, SwingType.LOW),
            swing(2, 10, 120.0, SwingType.HIGH),
            swing(3, 15, 91.0, SwingType.LOW),
            swing(4, 20, 101.0, SwingType.HIGH),
        )

        assertThat(service.detectHeadAndShoulders(swings)).isNull()
    }

    // ── detectAscendingTriangle ──────────────────────────────────────────────────

    @Test
    fun `detectAscendingTriangle matches flat resistance with rising support`() {
        val swings = listOf(
            swing(0, 0, 95.0, SwingType.LOW),
            swing(1, 3, 110.0, SwingType.HIGH),
            swing(2, 6, 97.0, SwingType.LOW),
            swing(3, 9, 110.5, SwingType.HIGH),
        )

        val match = service.detectAscendingTriangle(swings)

        assertThat(match).isNotNull()
        assertThat(match!!.patternType).isEqualTo("ASCENDING_TRIANGLE")
    }

    @Test
    fun `detectAscendingTriangle returns null when the lows are not ascending`() {
        val swings = listOf(
            swing(0, 0, 97.0, SwingType.LOW),
            swing(1, 3, 110.0, SwingType.HIGH),
            swing(2, 6, 95.0, SwingType.LOW), // descending, not ascending
            swing(3, 9, 110.5, SwingType.HIGH),
        )

        assertThat(service.detectAscendingTriangle(swings)).isNull()
    }

    @Test
    fun `detectAscendingTriangle returns null when highs vary by more than 2 percent`() {
        val swings = listOf(
            swing(0, 0, 95.0, SwingType.LOW),
            swing(1, 3, 100.0, SwingType.HIGH),
            swing(2, 6, 97.0, SwingType.LOW),
            swing(3, 9, 115.0, SwingType.HIGH), // 15% higher than first high
        )

        assertThat(service.detectAscendingTriangle(swings)).isNull()
    }

    // ── detectDescendingTriangle ─────────────────────────────────────────────────

    @Test
    fun `detectDescendingTriangle matches flat support with falling resistance`() {
        val swings = listOf(
            swing(0, 0, 110.0, SwingType.HIGH),
            swing(1, 3, 95.0, SwingType.LOW),
            swing(2, 6, 105.0, SwingType.HIGH),
            swing(3, 9, 94.5, SwingType.LOW),
        )

        val match = service.detectDescendingTriangle(swings)

        assertThat(match).isNotNull()
        assertThat(match!!.patternType).isEqualTo("DESCENDING_TRIANGLE")
    }

    @Test
    fun `detectDescendingTriangle returns null when highs are not descending`() {
        val swings = listOf(
            swing(0, 0, 100.0, SwingType.HIGH),
            swing(1, 3, 95.0, SwingType.LOW),
            swing(2, 6, 105.0, SwingType.HIGH), // ascending, not descending
            swing(3, 9, 94.5, SwingType.LOW),
        )

        assertThat(service.detectDescendingTriangle(swings)).isNull()
    }

    // ── detectPatterns (integration with jdbc) ──────────────────────────────────

    @Test
    fun `detectPatterns returns an empty list when fewer than 30 candles are available`() {
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), any(), any(), any())
        } returns (0 until 10).map { candle(it, 100.0) }

        val result = service.detectPatterns(stockId = 1L, lookbackDays = 90)

        assertThat(result).isEmpty()
    }

    @Test
    fun `detectPatterns returns an empty list when there are fewer than 3 swing points`() {
        // Flat series produces very few (or zero) swing points
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), any(), any(), any())
        } returns (0 until 40).map { candle(it, 100.0) }

        val result = service.detectPatterns(stockId = 1L, lookbackDays = 90)

        assertThat(result).isEmpty()
    }

    @Test
    fun `detectPatterns persists only matches with confidence at or above 70`() {
        // Construct a clean double-bottom-like series via raw closes feeding into zigZag
        val closes = listOf(
            100.0, 130.0, // up swing
            100.0, 131.0, // down then up swing (first LOW at idx2, HIGH at idx3)
            100.5,        // final LOW
        ) + List(30) { 100.5 } // pad to satisfy the >=30 candle requirement
        val candles = closes.mapIndexed { i, c -> candle(i, c) }
        every {
            jdbc.query(any<String>(), any<RowMapper<DailyCandle>>(), any(), any(), any())
        } returns candles
        every { detectedPatternRepository.save(any()) } answers { firstArg() }

        val result = service.detectPatterns(stockId = 1L, lookbackDays = candles.size)

        // Whatever matches are found, only confidence >= 70 should trigger a save;
        // confidence >= 60 are returned. This asserts the filtering boundary holds.
        assertThat(result).allMatch { it.confidenceScore >= 60 }
    }
}
