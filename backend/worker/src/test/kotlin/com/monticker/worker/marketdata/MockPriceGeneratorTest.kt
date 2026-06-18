package com.monticker.worker.marketdata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MockPriceGeneratorTest {

    private val generator = MockPriceGenerator()

    @Test
    fun `generates ticks for all tracked symbols`() {
        val ticks = generator.generate()
        assertThat(ticks).hasSize(5)
        assertThat(ticks.map { it.symbol }).contains("005930", "AAPL", "NVDA")
    }

    @Test
    fun `price stays positive after multiple generations`() {
        repeat(100) { generator.generate() }
        val ticks = generator.generate()
        ticks.forEach { assertThat(it.price).isPositive() }
    }
}
