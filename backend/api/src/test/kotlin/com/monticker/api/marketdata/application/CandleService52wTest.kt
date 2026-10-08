package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.PriceRange52w
import com.monticker.api.marketdata.infrastructure.CandleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class CandleService52wTest {

    private val repo = mockk<CandleRepository>()
    private val service = CandleService(repo)

    @Test
    fun `window is KST midnight 52 weeks ago up to KST midnight tomorrow`() {
        val from = slot<Instant>()
        val to = slot<Instant>()
        every { repo.findRanges(any(), capture(from), capture(to)) } returns emptyList()

        service.get52WeekRanges(listOf(1L), today = LocalDate.of(2026, 10, 8))

        // 2025-10-09 00:00 KST = 2025-10-08T15:00Z, 2026-10-09 00:00 KST = 2026-10-08T15:00Z
        assertThat(from.captured).isEqualTo(Instant.parse("2025-10-08T15:00:00Z"))
        assertThat(to.captured).isEqualTo(Instant.parse("2026-10-08T15:00:00Z"))
    }

    @Test
    fun `first and last candle dates are KST dates and short history is flagged`() {
        every { repo.findRanges(any(), any(), any()) } returns listOf(
            // KST 자정 일봉 — UTC로는 전날 15:00
            CandleRepository.RangeRow(1L, BigDecimal("120"), BigDecimal("80"), Instant.parse("2025-10-09T15:00:00Z"), Instant.parse("2026-10-07T15:00:00Z"), 245),
            CandleRepository.RangeRow(2L, BigDecimal("50"), BigDecimal("40"), Instant.parse("2026-06-30T15:00:00Z"), Instant.parse("2026-10-07T15:00:00Z"), 70),
        )

        val r = service.get52WeekRanges(listOf(1L, 2L), today = LocalDate.of(2026, 10, 8))

        assertThat(r[1L]!!.firstDate).isEqualTo(LocalDate.of(2025, 10, 10))
        assertThat(r[1L]!!.lastDate).isEqualTo(LocalDate.of(2026, 10, 8))
        assertThat(r[1L]!!.from).isEqualTo(LocalDate.of(2025, 10, 9))
        assertThat(r[1L]!!.fullPeriod).isTrue()
        assertThat(r[2L]!!.firstDate).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(r[2L]!!.fullPeriod).isFalse()
    }

    @Test
    fun `no stocks means no query`() {
        assertThat(service.get52WeekRanges(emptyList())).isEmpty()
        verify(exactly = 0) { repo.findRanges(any(), any(), any()) }
    }

    @Test
    fun `full period tolerates a holiday-length gap at the window start`() {
        val from = LocalDate.of(2025, 10, 9)
        fun range(first: LocalDate) = PriceRange52w(1L, BigDecimal.ONE, BigDecimal.ONE, from, first, first, 1)
        assertThat(range(from.plusDays(PriceRange52w.FULL_PERIOD_TOLERANCE_DAYS)).fullPeriod).isTrue()
        assertThat(range(from.plusDays(PriceRange52w.FULL_PERIOD_TOLERANCE_DAYS + 1)).fullPeriod).isFalse()
    }
}
