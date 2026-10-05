package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.StockPriceProvider
import com.monticker.api.marketdata.infrastructure.CandleRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class MarketDataServiceTest {

    private val provider = mockk<StockPriceProvider>()
    private val candles = mockk<CandleRepository>()
    private val service = MarketDataService(provider, candles)

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

    @Test
    fun `등락률은 그 가격이 난 거래일(KST)의 직전 거래일 종가 기준이다`() {
        val tradeTime = Instant.parse("2026-10-05T02:00:00Z")   // KST 10-05 11:00
        every { provider.getLatestPrice(1L, "005930") } returns PriceTick(1L, "005930", BigDecimal("71400"), 10L, tradeTime)
        every { candles.findPreviousClose(1L, Instant.parse("2026-10-04T15:00:00Z"), any()) } returns BigDecimal("70000")

        val view = service.getPriceView(1L, "005930", "KOSPI")!!

        assertThat(view.prevClose).isEqualByComparingTo("70000")
        assertThat(view.changeRate).isEqualTo(2.0)
    }

    @Test
    fun `전일 종가를 모르거나 해외 종목이면 등락은 null - 0%로 위장하지 않는다`() {
        every { provider.getLatestPrice(any(), any()) } returns PriceTick(1L, "X", BigDecimal("100"), 1L, Instant.now())
        every { candles.findPreviousClose(any(), any(), any()) } returns null

        assertThat(service.getPriceView(1L, "005930", "KOSPI")!!.changeRate).isNull()
        assertThat(service.getPriceView(1L, "AAPL", "NASDAQ")!!.prevClose).isNull()
    }
}
