package com.monticker.api.marketdata.infrastructure.orderbook

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MockOrderBookProviderTest {

    private val provider = MockOrderBookProvider()

    @Test
    fun `produces 10 ask levels and 10 bid levels`() {
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result.asks).hasSize(10)
        assertThat(result.bids).hasSize(10)
    }

    @Test
    fun `tags the snapshot with source MOCK`() {
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result.source).isEqualTo(DataSource.MOCK)
    }

    @Test
    fun `ask prices increase moving away from the reference price`() {
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        val prices = result.asks.map { it.price }
        assertThat(prices).isSorted()
        assertThat(prices.first()).isGreaterThan(BigDecimal("70000"))
    }

    @Test
    fun `bid prices decrease moving away from the reference price`() {
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        val prices = result.bids.map { it.price }
        assertThat(prices).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(prices.first()).isLessThan(BigDecimal("70000"))
    }

    @Test
    fun `all quantities are positive`() {
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))

        assertThat(result.asks).allMatch { it.quantity > 0 }
        assertThat(result.bids).allMatch { it.quantity > 0 }
    }

    @Test
    fun `prices are rounded to the appropriate KRX tick size for a sub-1000-won stock`() {
        val result = provider.getOrderBook("000001", "KOSPI", BigDecimal("500"))

        // tick size for [500, 1000) is 1 won — all ask/bid prices must be whole numbers
        result.asks.forEach { level ->
            assertThat(level.price.remainder(BigDecimal.ONE)).isEqualByComparingTo(BigDecimal.ZERO)
        }
    }

    @Test
    fun `prices are rounded to a coarser tick size for a high-priced stock`() {
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("600000"))

        // tick size for >= 500,000 is 1,000 won
        result.asks.forEach { level ->
            assertThat(level.price.remainder(BigDecimal("1000"))).isEqualByComparingTo(BigDecimal.ZERO)
        }
    }

    @Test
    fun `produces a fresh timestamp close to now`() {
        val before = java.time.Instant.now()
        val result = provider.getOrderBook("005930", "KOSPI", BigDecimal("70000"))
        val after = java.time.Instant.now()

        assertThat(result.updatedAt).isBetween(before, after)
    }
}
