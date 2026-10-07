package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.Candle
import com.monticker.api.marketdata.infrastructure.CandleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CandleServiceTest {

    private val repo = mockk<CandleRepository>()
    private val service = CandleService(repo)

    @Test
    fun `returns candles for 1d interval`() {
        every { repo.findCandles(1L, "candles_1d", any(), any()) } returns listOf(makeCandle())

        val result = service.getCandles(1L, "1d")

        assertThat(result).hasSize(1)
        verify { repo.findCandles(1L, "candles_1d", any(), any()) }
    }

    @Test
    fun `returns candles for 1m interval`() {
        every { repo.findCandles(1L, "candles_1m", any(), any()) } returns emptyList()

        val result = service.getCandles(1L, "1m")

        assertThat(result).isEmpty()
        verify { repo.findCandles(1L, "candles_1m", any(), any()) }
    }

    // ADR-076 — 3분·15분·1시간은 분봉 집계로 조회한다.
    @Test
    fun `aggregates 3m 15m and 1h from minute candles`() {
        every { repo.findBucketed(1L, any(), any(), any()) } returns listOf(makeCandle())

        assertThat(service.getCandles(1L, "3m")).hasSize(1)
        service.getCandles(1L, "15m")
        service.getCandles(1L, "1h")

        verify { repo.findBucketed(1L, 3, any(), any()) }
        verify { repo.findBucketed(1L, 15, any(), any()) }
        verify { repo.findBucketed(1L, 60, any(), any()) }
        verify(exactly = 0) { repo.findCandles(any(), any(), any(), any()) }
    }

    @Test
    fun `intraday returns the whole KST day of minute candles`() {
        val from = io.mockk.slot<Instant>()
        val to = io.mockk.slot<Instant>()
        every { repo.findCandles(1L, "candles_1m", capture(from), capture(to), 600) } returns listOf(makeCandle())

        assertThat(service.getIntradayCandles(1L, java.time.LocalDate.of(2026, 10, 2))).hasSize(1)
        assertThat(from.captured).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"))
        assertThat(to.captured).isBefore(Instant.parse("2026-10-02T15:00:00Z"))
    }

    @Test
    fun `intraday rejects a future date`() {
        assertThatThrownBy { service.getIntradayCandles(1L, java.time.LocalDate.now().plusDays(3)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `throws on unsupported interval`() {
        assertThatThrownBy { service.getCandles(1L, "5m") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun makeCandle() = Candle(
        stockId = 1L,
        open = BigDecimal("70000"), high = BigDecimal("71000"),
        low = BigDecimal("69500"),  close = BigDecimal("70800"),
        volume = 10000L, time = Instant.now(),
    )
}
