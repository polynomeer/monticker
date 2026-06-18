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
