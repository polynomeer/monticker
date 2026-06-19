package com.monticker.api.stock.api

import com.monticker.api.stock.application.StockService
import com.monticker.api.stock.domain.Market
import com.monticker.api.stock.domain.Stock
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class StockControllerTest {

    private val stockService = mockk<StockService>()
    private val controller = StockController(stockService)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    @Test
    fun `GET search returns matching stocks`() {
        every { stockService.search("삼성") } returns listOf(
            Stock(id = 1L, symbol = "005930", name = "삼성전자", market = Market.KOSPI, exchange = "KRX")
        )

        mockMvc.perform(get("/api/stocks/search").param("query", "삼성"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].symbol").value("005930"))
            .andExpect(jsonPath("$[0].name").value("삼성전자"))
    }

    @Test
    fun `GET search returns 400 when query is missing`() {
        mockMvc.perform(get("/api/stocks/search"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET by id returns stock`() {
        every { stockService.getById(1L) } returns
            Stock(id = 1L, symbol = "005930", name = "삼성전자", market = Market.KOSPI, exchange = "KRX")

        mockMvc.perform(get("/api/stocks/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
    }

    @Test
    fun `GET by id returns 404 when not found`() {
        every { stockService.getById(99L) } throws NoSuchElementException("not found")

        mockMvc.perform(get("/api/stocks/99"))
            .andExpect(status().isNotFound)
    }
}
