package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.StockPriceProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class MarketDataServiceTest {

    private val provider = mockk<StockPriceProvider>()
    private val service = MarketDataService(provider)

    @Test
    fun `getLatestPrice returns tick from provider`() {
        val tick = PriceTick(1L, "005930", BigDecimal("71000"), 10000L, Instant.now())
        every { provider.getLatestPrice(1L, "005930") } returns tick

        val result = service.getLatestPrice(1L, "005930")

        assertThat(result).isNotNull
        assertThat(result!!.price).isEqualByComparingTo("71000")
    }

    @Test
    fun `getLatestPrice returns null when no data`() {
        every { provider.getLatestPrice(1L, "005930") } returns null

        val result = service.getLatestPrice(1L, "005930")

        assertThat(result).isNull()
    }
}
