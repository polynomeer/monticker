package com.monticker.api.watchlist.application

import com.monticker.api.common.metrics.SearchMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.monticker.api.stock.application.StockService
import com.monticker.api.stock.domain.Market
import com.monticker.api.stock.domain.Stock
import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.domain.WatchlistItem
import com.monticker.api.watchlist.infrastructure.WatchlistGroupRepository
import com.monticker.api.watchlist.infrastructure.WatchlistItemRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class WatchlistServiceTest {

    private val groupRepository = mockk<WatchlistGroupRepository>()
    private val itemRepository = mockk<WatchlistItemRepository>()
    private val stockService = mockk<StockService>()
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = WatchlistService(groupRepository, itemRepository, stockService, esOps, SearchMetrics(SimpleMeterRegistry()), events)

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
        every { stockService.getById(1L) } returns stock
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

    // ADR-042 — 이 서비스는 ES writer가 아니다. 삭제는 SearchIndexEvent.delete 를 같은 트랜잭션에 발행한다.
    @Test
    fun `removeItem publishes a delete index event instead of calling Elasticsearch`() {
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "g")
        val stock = mockk<com.monticker.api.stock.domain.Stock>(relaxed = true)
        val item = WatchlistItem(id = 42L, group = group, stock = stock, memo = null)
        every { itemRepository.findById(42L) } returns java.util.Optional.of(item)
        every { itemRepository.delete(item) } returns Unit
        val published = slot<Any>()
        every { events.publishEvent(capture(published)) } returns Unit

        service.removeItem(1L, 42L)

        val ev = published.captured as com.monticker.api.common.search.SearchIndexEvent
        org.assertj.core.api.Assertions.assertThat(ev.index).isEqualTo("watchlist_items")
        org.assertj.core.api.Assertions.assertThat(ev.docId).isEqualTo("42")
        org.assertj.core.api.Assertions.assertThat(ev.op).isEqualTo(com.monticker.api.common.search.SearchIndexEvent.Op.DELETE)
        verify(exactly = 0) { esOps.delete(any<String>(), any<Class<*>>()) }
    }
}
