package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.infrastructure.orderbook.DataSource
import com.monticker.api.marketdata.infrastructure.orderbook.KisOrderBookProvider
import com.monticker.api.marketdata.infrastructure.orderbook.OrderBookSnapshot
import com.monticker.api.marketdata.infrastructure.orderbook.OrderLevel
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KisBestQuoteSourceTest {

    private val now = Instant.parse("2026-10-08T01:00:00Z")
    private val jdbc = mockk<JdbcTemplate>()
    private val kis = mockk<KisOrderBookProvider>()
    private val source = KisBestQuoteSource(jdbc, kis).apply { clock = Clock.fixed(now, ZoneOffset.UTC) }

    private fun stubStock() {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, String>>>(), 1L) } returns listOf("005930" to "KOSPI")
    }

    private fun snap(updatedAt: Instant, bids: List<String> = listOf("69900"), asks: List<String> = listOf("70000")) = OrderBookSnapshot(
        asks = asks.map { OrderLevel(BigDecimal(it), 10) }, bids = bids.map { OrderLevel(BigDecimal(it), 10) },
        updatedAt = updatedAt, source = DataSource.KIS_REALTIME,
    )

    @Test
    fun `returns the top of book from a fresh KIS snapshot`() {
        stubStock()
        every { kis.getOrderBook("005930", "KOSPI", any()) } returns snap(now.minusSeconds(2), bids = listOf("69900", "69800"), asks = listOf("70000", "70100"))
        val q = source.bestQuote(1L)!!
        assertThat(q.bid).isEqualByComparingTo("69900")
        assertThat(q.ask).isEqualByComparingTo("70000")
        assertThat(q.source).isEqualTo("KIS_REALTIME")
    }

    @Test
    fun `no real-time quote, a stale snapshot or an empty book gives no quote`() {
        stubStock()
        every { kis.getOrderBook(any(), any(), any()) } returns null
        assertThat(source.bestQuote(1L)).isNull()

        every { kis.getOrderBook(any(), any(), any()) } returns snap(now.minusSeconds(31))
        assertThat(source.bestQuote(1L)).isNull()

        every { kis.getOrderBook(any(), any(), any()) } returns snap(now, bids = emptyList(), asks = emptyList())
        assertThat(source.bestQuote(1L)).isNull()
    }

    @Test
    fun `lookup failures are swallowed so the order is not blocked`() {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, String>>>(), 1L) } throws RuntimeException("db")
        assertThat(source.bestQuote(1L)).isNull()
    }
}
