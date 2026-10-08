package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.infrastructure.orderbook.KisOrderBookProvider
import com.monticker.api.marketdata.infrastructure.orderbook.MockOrderBookProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

class OrderBookServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val kis = mockk<KisOrderBookProvider> { every { getOrderBook(any(), any(), any()) } returns null }
    private val service = OrderBookService(jdbc, kis, null, MockOrderBookProvider())

    private fun stockRow(vararg rows: Map<String, Any?>) {
        every { jdbc.queryForList(match<String> { it.contains("FROM stocks") }, 2L) } returns rows.toList()
    }

    private fun latestClose(vararg closes: BigDecimal) {
        every { jdbc.query(match<String> { it.contains("candles_1m") }, any<RowMapper<BigDecimal>>(), 2L) } returns closes.toList()
    }

    @Test
    fun `an unknown stock is a 404 not a 500`() {
        stockRow()

        assertThatThrownBy { service.getOrderBook(2L) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `a stock without any candle is a 404 not a 500`() {
        stockRow(mapOf("symbol" to "005930", "name" to "삼성전자", "market" to "KOSPI"))
        latestClose()

        assertThatThrownBy { service.getOrderBook(2L) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `a priced stock falls back to the mock book when no live provider answers`() {
        stockRow(mapOf("symbol" to "005930", "name" to "삼성전자", "market" to "KOSPI"))
        latestClose(BigDecimal("70000"))

        val book = service.getOrderBook(2L)

        assertThat(book.symbol).isEqualTo("005930")
        assertThat(book.asks).isNotEmpty
        assertThat(book.bids).isNotEmpty
    }
}
