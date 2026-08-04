package com.monticker.api.screener.application

import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.ScreenerRepository
import com.monticker.api.stock.application.StockSearchService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ScreenerServiceTest {

    private val repo = mockk<ScreenerRepository>()
    private val stockSearchService = mockk<StockSearchService>(relaxed = true)
    private val service = ScreenerService(repo, stockSearchService)

    private fun item(rank: Int, stockId: Long) = ScreenerItem(
        rank = rank, stockId = stockId, symbol = "00$stockId", name = "종목$stockId",
        market = "KOSPI", sector = null, price = BigDecimal("10000"), prevClose = BigDecimal("9900"),
        changeRate = 1.0, changeAmount = BigDecimal("100"), volume = 1000L, amount = BigDecimal("10000000"),
        buyRatio = 55, sellRatio = 45,
    )

    @Test
    fun `getItems delegates to the repository with the requested market and sort`() {
        every { repo.findItems("domestic", "amount", 20, 0) } returns listOf(item(1, 1L))
        every { repo.count("domestic") } returns 1

        val result = service.getItems(tab = "realtime", market = "domestic", sort = "amount")

        assertThat(result.items).hasSize(1)
        verify { repo.findItems("domestic", "amount", 20, 0) }
    }

    @Test
    fun `movers tab defaults to rise sort when an unrelated sort value is requested`() {
        every { repo.findItems("all", "rise", any(), any()) } returns emptyList()
        every { repo.count("all") } returns 0

        service.getItems(tab = "movers", sort = "amount")

        verify { repo.findItems("all", "rise", any(), any()) }
    }

    @Test
    fun `movers tab preserves fall sort when explicitly requested`() {
        every { repo.findItems("all", "fall", any(), any()) } returns emptyList()
        every { repo.count("all") } returns 0

        service.getItems(tab = "movers", sort = "fall")

        verify { repo.findItems("all", "fall", any(), any()) }
    }

    @Test
    fun `non-movers tabs pass the requested sort through unchanged`() {
        every { repo.findItems("all", "volume", any(), any()) } returns emptyList()
        every { repo.count("all") } returns 0

        service.getItems(tab = "realtime", sort = "volume")

        verify { repo.findItems("all", "volume", any(), any()) }
    }

    @Test
    fun `limit is clamped to a maximum of 50`() {
        every { repo.findItems(any(), any(), 50, any()) } returns emptyList()
        every { repo.count(any()) } returns 0

        service.getItems(limit = 500)

        verify { repo.findItems(any(), any(), 50, any()) }
    }

    @Test
    fun `limit is clamped to a minimum of 1`() {
        every { repo.findItems(any(), any(), 1, any()) } returns emptyList()
        every { repo.count(any()) } returns 0

        service.getItems(limit = 0)

        verify { repo.findItems(any(), any(), 1, any()) }
    }

    @Test
    fun `hasMore is true when there are more rows beyond the current page`() {
        every { repo.findItems(any(), any(), any(), any()) } returns listOf(item(1, 1L), item(2, 2L))
        every { repo.count(any()) } returns 10

        val result = service.getItems(limit = 2, offset = 0)

        assertThat(result.hasMore).isTrue()
    }

    @Test
    fun `hasMore is false when the current page reaches the end of the result set`() {
        every { repo.findItems(any(), any(), any(), any()) } returns listOf(item(1, 1L))
        every { repo.count(any()) } returns 1

        val result = service.getItems(limit = 20, offset = 0)

        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `total reflects the repository count regardless of page size`() {
        every { repo.findItems(any(), any(), any(), any()) } returns listOf(item(1, 1L))
        every { repo.count(any()) } returns 202

        val result = service.getItems(limit = 1)

        assertThat(result.total).isEqualTo(202)
    }
}
