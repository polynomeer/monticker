package com.monticker.api.stock.application

import com.monticker.api.stock.domain.Market
import com.monticker.api.stock.domain.Stock
import com.monticker.api.stock.infrastructure.StockRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class StockServiceTest {

    private val stockRepository = mockk<StockRepository>()
    private val stockService = StockService(stockRepository)

    @Test
    fun `search returns matching stocks`() {
        val stock = createStock("005930", "삼성전자")
        every { stockRepository.searchByNameOrSymbol("삼성") } returns listOf(stock)

        val result = stockService.search("삼성")

        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("삼성전자")
    }

    @Test
    fun `search throws when query is blank`() {
        assertThatThrownBy { stockService.search("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `getById throws when stock not found`() {
        every { stockRepository.findById(999L) } returns Optional.empty()

        assertThatThrownBy { stockService.getById(999L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    private fun createStock(symbol: String, name: String) = Stock(
        id = 1L,
        symbol = symbol,
        name = name,
        market = Market.KOSPI,
        exchange = "KRX",
    )
}
