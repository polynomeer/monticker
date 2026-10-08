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
import com.monticker.api.watchlist.infrastructure.WatchlistOrderRepository
import com.monticker.api.marketdata.application.CandleService
import com.monticker.api.marketdata.domain.PriceRange52w
import java.math.BigDecimal
import java.time.LocalDate
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
    // relaxed: lockGroup=false, lockGroupOfItem=null — 잠금 단계에서 "없음"이 기본값
    private val orderRepository = mockk<WatchlistOrderRepository>(relaxed = true)
    private val candleService = mockk<CandleService>()
    private val service = WatchlistService(
        groupRepository, itemRepository, stockService, esOps, SearchMetrics(SimpleMeterRegistry()), events,
        orderRepository, candleService,
    )

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

        every { orderRepository.lockGroup(1L, 1L) } returns true
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
        every { itemRepository.flush() } returns Unit
        every { orderRepository.lockGroupOfItem(1L, 42L) } returns 1L
        val published = slot<Any>()
        every { events.publishEvent(capture(published)) } returns Unit

        service.removeItem(1L, 42L)

        val ev = published.captured as com.monticker.api.common.search.SearchIndexEvent
        org.assertj.core.api.Assertions.assertThat(ev.index).isEqualTo("watchlist_items")
        org.assertj.core.api.Assertions.assertThat(ev.docId).isEqualTo("42")
        org.assertj.core.api.Assertions.assertThat(ev.op).isEqualTo(com.monticker.api.common.search.SearchIndexEvent.Op.DELETE)
        verify(exactly = 0) { esOps.delete(any<String>(), any<Class<*>>()) }
    }

    // ── 소유권: 남의 그룹·항목은 없는 것과 구분되지 않는다(id 열거 방지) ─────────────────

    @Test
    fun `addItem to another user's group looks exactly like a missing group`() {
        every { orderRepository.lockGroup(1L, 1L) } returns true
        every { groupRepository.findById(1L) } returns Optional.of(WatchlistGroup(id = 1L, userId = 2L, name = "남의 목록"))
        val others = runCatching { service.addItem(1L, 1L, 1L, null) }.exceptionOrNull()
        every { groupRepository.findById(1L) } returns Optional.empty()
        val missing = runCatching { service.addItem(1L, 1L, 1L, null) }.exceptionOrNull()

        org.assertj.core.api.Assertions.assertThat(others).isInstanceOf(NoSuchElementException::class.java)
        org.assertj.core.api.Assertions.assertThat(others!!.message).isEqualTo(missing!!.message)
        verify(exactly = 0) { itemRepository.save(any()) }
    }

    @Test
    fun `removeItem of another user's item looks exactly like a missing item`() {
        val group = WatchlistGroup(id = 1L, userId = 2L, name = "남의 목록")
        val item = WatchlistItem(id = 42L, group = group, stock = mockk(relaxed = true), memo = null)
        every { itemRepository.findById(42L) } returns Optional.of(item)
        val others = runCatching { service.removeItem(1L, 42L) }.exceptionOrNull()
        every { itemRepository.findById(42L) } returns Optional.empty()
        val missing = runCatching { service.removeItem(1L, 42L) }.exceptionOrNull()

        org.assertj.core.api.Assertions.assertThat(others).isInstanceOf(NoSuchElementException::class.java)
        org.assertj.core.api.Assertions.assertThat(others!!.message).isEqualTo(missing!!.message)
        verify(exactly = 0) { itemRepository.delete(any()) }
    }

    // ── 그룹 삭제 ─────────────────────────────────────────────────────────

    @Test
    fun `deleteGroup of another user's or a missing group is the same 404 and deletes nothing`() {
        every { orderRepository.deleteGroup(1L, 5L) } returns null   // 남의 그룹(소유자 조건으로 잠금 실패)
        every { orderRepository.deleteGroup(1L, 6L) } returns null   // 없는 그룹
        val others = runCatching { service.deleteGroup(1L, 5L) }.exceptionOrNull()
        val missing = runCatching { service.deleteGroup(1L, 6L) }.exceptionOrNull()

        org.assertj.core.api.Assertions.assertThat(others).isInstanceOf(NoSuchElementException::class.java)
        org.assertj.core.api.Assertions.assertThat(others!!.message!!.replace("5", "")).isEqualTo(missing!!.message!!.replace("6", ""))
        verify(exactly = 0) { events.publishEvent(any<Any>()) }
    }

    @Test
    fun `deleteGroup removes every item of the group from the search index`() {
        every { orderRepository.deleteGroup(1L, 5L) } returns listOf(11L, 12L)
        val published = mutableListOf<Any>()
        every { events.publishEvent(capture(published)) } returns Unit

        service.deleteGroup(1L, 5L)

        val docIds = published.map { (it as com.monticker.api.common.search.SearchIndexEvent).also { e ->
            org.assertj.core.api.Assertions.assertThat(e.op).isEqualTo(com.monticker.api.common.search.SearchIndexEvent.Op.DELETE)
        }.docId }
        org.assertj.core.api.Assertions.assertThat(docIds).containsExactly("11", "12")
    }

    // ── 순서 ─────────────────────────────────────────────────────────────

    @Test
    fun `addItem appends at the end of the group under the group lock`() {
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "g")
        val stock = Stock(id = 7L, symbol = "005930", name = "삼성전자", market = Market.KOSPI, exchange = "KRX")
        every { orderRepository.lockGroup(1L, 1L) } returns true
        every { orderRepository.nextSortOrder(1L) } returns 3
        every { groupRepository.findById(1L) } returns Optional.of(group)
        every { stockService.getById(7L) } returns stock
        every { itemRepository.existsByGroupIdAndStockId(1L, 7L) } returns false
        val saved = slot<WatchlistItem>()
        every { itemRepository.save(capture(saved)) } answers { saved.captured }

        service.addItem(1L, 1L, 7L, null)

        org.assertj.core.api.Assertions.assertThat(saved.captured.sortOrder).isEqualTo(3)
        io.mockk.verifyOrder {
            orderRepository.lockGroup(1L, 1L)
            orderRepository.nextSortOrder(1L)
        }
    }

    @Test
    fun `removeItem compacts the remaining order after deleting`() {
        val group = WatchlistGroup(id = 5L, userId = 1L, name = "g")
        val item = WatchlistItem(id = 42L, group = group, stock = mockk(relaxed = true), memo = null)
        every { itemRepository.findById(42L) } returns Optional.of(item)
        every { orderRepository.lockGroupOfItem(1L, 42L) } returns 5L
        every { itemRepository.delete(item) } returns Unit
        every { itemRepository.flush() } returns Unit

        service.removeItem(1L, 42L)

        io.mockk.verifyOrder {
            orderRepository.lockGroupOfItem(1L, 42L)
            itemRepository.delete(item)
            itemRepository.flush()
            orderRepository.compact(5L)
        }
    }

    // ── 52주 고저 ─────────────────────────────────────────────────────────

    @Test
    fun `get52WeekRanges asks once for every distinct stock across groups`() {
        val s1 = Stock(id = 1L, symbol = "A", name = "a", market = Market.KOSPI, exchange = "KRX")
        val s2 = Stock(id = 2L, symbol = "B", name = "b", market = Market.KOSPI, exchange = "KRX")
        val g1 = WatchlistGroup(id = 1L, userId = 1L, name = "g1").apply {
            items += WatchlistItem(id = 10L, group = this, stock = s1)
            items += WatchlistItem(id = 11L, group = this, stock = s2)
        }
        val g2 = WatchlistGroup(id = 2L, userId = 1L, name = "g2").apply { items += WatchlistItem(id = 12L, group = this, stock = s1) }
        val range = PriceRange52w(1L, BigDecimal.TEN, BigDecimal.ONE, LocalDate.of(2025, 10, 9), LocalDate.of(2025, 10, 10), LocalDate.of(2026, 10, 8), 240)
        every { candleService.get52WeekRanges(setOf(1L, 2L), any()) } returns mapOf(1L to range)

        val result = service.get52WeekRanges(listOf(g1, g2))

        org.assertj.core.api.Assertions.assertThat(result).containsEntry(1L, range).doesNotContainKey(2L)
        verify(exactly = 1) { candleService.get52WeekRanges(any(), any()) }
    }

    @Test
    fun `get52WeekRanges failure degrades to an empty map so the list still renders`() {
        val s1 = Stock(id = 1L, symbol = "A", name = "a", market = Market.KOSPI, exchange = "KRX")
        val g = WatchlistGroup(id = 1L, userId = 1L, name = "g").apply { items += WatchlistItem(id = 10L, group = this, stock = s1) }
        every { candleService.get52WeekRanges(any(), any()) } throws RuntimeException("db down")

        org.assertj.core.api.Assertions.assertThat(service.get52WeekRanges(listOf(g))).isEmpty()
    }

    @Test
    fun `get52WeekRanges skips the query when there are no items`() {
        org.assertj.core.api.Assertions.assertThat(service.get52WeekRanges(listOf(WatchlistGroup(id = 1L, userId = 1L, name = "g")))).isEmpty()
        verify(exactly = 0) { candleService.get52WeekRanges(any(), any()) }
    }
}
