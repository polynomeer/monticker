package com.monticker.api.watchlist.application

import com.monticker.api.stock.domain.Market
import com.monticker.api.stock.domain.Stock
import com.monticker.api.stock.infrastructure.StockRepository
import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.infrastructure.WatchlistGroupRepository
import com.monticker.api.watchlist.infrastructure.WatchlistItemRepository
import com.monticker.api.watchlist.infrastructure.WatchlistSearchRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class WatchlistServiceTest {

    private val groupRepository = mockk<WatchlistGroupRepository>()
    private val itemRepository = mockk<WatchlistItemRepository>()
    private val stockRepository = mockk<StockRepository>()
    private val watchlistSearchRepository = mockk<WatchlistSearchRepository>(relaxed = true)
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val service = WatchlistService(groupRepository, itemRepository, stockRepository, watchlistSearchRepository, esOps)

    @Test
    fun `createGroup throws when name is blank`() {
        assertThatThrownBy { service.createGroup(1L, "  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `addItem throws when group not found`() {
        every { groupRepository.findById(99L) } returns Optional.empty()

        assertThatThrownBy { service.addItem(1L, 99L, 1L, null) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `addItem throws when stock already in watchlist`() {
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "My List")
        val stock = Stock(id = 1L, symbol = "005930", name = "삼성전자", market = Market.KOSPI, exchange = "KRX")

        every { groupRepository.findById(1L) } returns Optional.of(group)
        every { stockRepository.findById(1L) } returns Optional.of(stock)
        every { itemRepository.existsByGroupIdAndStockId(1L, 1L) } returns true

        assertThatThrownBy { service.addItem(1L, 1L, 1L, null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `removeItem throws when item not found`() {
        every { itemRepository.findById(99L) } returns Optional.empty()

        assertThatThrownBy { service.removeItem(1L, 99L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
