package com.monticker.api.screener.application

import com.monticker.api.common.metrics.SearchMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.monticker.api.quant.application.QuantSignalFeedService
import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.domain.ScreenerEventFilter
import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.ScreenerDayContext
import com.monticker.api.screener.infrastructure.ScreenerRepository
import com.monticker.api.stock.application.StockSearchService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ScreenerServiceTest {

    private val repo = mockk<ScreenerRepository>()
    private val stockSearchService = mockk<StockSearchService>(relaxed = true)
    private val quant = mockk<QuantSignalFeedService>()
    private val service = ScreenerService(repo, stockSearchService, SearchMetrics(SimpleMeterRegistry()), quant)

    private fun item(rank: Int, stockId: Long, market: String = "KOSPI", marketCap: Long? = null) = ScreenerItem(
        rank = rank, stockId = stockId, symbol = "00$stockId", name = "종목$stockId",
        market = market, sector = null, price = BigDecimal("10000"), prevClose = BigDecimal("9900"),
        changeRate = 1.0, changeAmount = BigDecimal("100"), volume = 1000L, amount = BigDecimal("10000000"),
        buyRatio = 55, sellRatio = 45,
        marketCap = marketCap, per = null, pbr = null, isFundamentalsMocked = false,
    )

    @BeforeEach
    fun stubDayContext() {
        every { repo.findDayContext(any(), any()) } returns emptyMap()
    }

    private fun stubFind(sort: String = "amount", items: List<ScreenerItem> = emptyList(), total: Int = items.size, limit: Int? = null) {
        every { repo.findItems(match { it.sort == sort }, limit ?: any(), any(), any(), any()) } returns items
        every { repo.count(any(), any(), any()) } returns total
    }

    @Test
    fun `getItems delegates to the repository with the requested market and sort`() {
        every { repo.findItems(ScreenerCriteria(market = "domestic"), 20, 0, null, any()) } returns listOf(item(1, 1L))
        every { repo.count(ScreenerCriteria(market = "domestic"), null, any()) } returns 1

        val result = service.getItems(tab = "realtime", criteria = ScreenerCriteria(market = "domestic"))

        assertThat(result.items).hasSize(1)
        verify { repo.findItems(ScreenerCriteria(market = "domestic"), 20, 0, null, any()) }
    }

    @Test
    fun `movers tab defaults to rise sort when an unrelated sort value is requested`() {
        stubFind(sort = "rise")

        service.getItems(tab = "movers", criteria = ScreenerCriteria(sort = "amount"))

        verify { repo.findItems(match { it.sort == "rise" }, any(), any(), any(), any()) }
    }

    @Test
    fun `movers tab preserves fall sort when explicitly requested`() {
        stubFind(sort = "fall")

        service.getItems(tab = "movers", criteria = ScreenerCriteria(sort = "fall"))

        verify { repo.findItems(match { it.sort == "fall" }, any(), any(), any(), any()) }
    }

    @Test
    fun `non-movers tabs pass the requested sort through unchanged`() {
        stubFind(sort = "volume")

        service.getItems(tab = "realtime", criteria = ScreenerCriteria(sort = "volume"))

        verify { repo.findItems(match { it.sort == "volume" }, any(), any(), any(), any()) }
    }

    @Test
    fun `limit is clamped to a maximum of 50`() {
        stubFind(limit = 50)

        service.getItems(limit = 500)

        verify { repo.findItems(any(), 50, any(), any(), any()) }
    }

    @Test
    fun `limit is clamped to a minimum of 1`() {
        stubFind(limit = 1)

        service.getItems(limit = 0)

        verify { repo.findItems(any(), 1, any(), any(), any()) }
    }

    @Test
    fun `hasMore is true when there are more rows beyond the current page`() {
        stubFind(items = listOf(item(1, 1L), item(2, 2L)), total = 10)

        val result = service.getItems(limit = 2, offset = 0)

        assertThat(result.hasMore).isTrue()
    }

    @Test
    fun `hasMore is false when the current page reaches the end of the result set`() {
        stubFind(items = listOf(item(1, 1L)), total = 1)

        val result = service.getItems(limit = 20, offset = 0)

        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `total reflects the repository count regardless of page size`() {
        stubFind(items = listOf(item(1, 1L)), total = 202)

        val result = service.getItems(limit = 1)

        assertThat(result.total).isEqualTo(202)
    }

    // ── ADR-072 조건·보조 정보 ──────────────────────────────────────────────

    @Test
    fun `page rows get volume multiple and today's events`() {
        stubFind(items = listOf(item(1, 1L)), total = 1)
        every { repo.findDayContext(listOf(1L), any()) } returns mapOf(1L to ScreenerDayContext(2.5, listOf("NEWS_PUBLISHED")))

        val row = service.getItems().items.single()

        assertThat(row.volumeMultiple).isEqualTo(2.5)
        assertThat(row.todayEvents).containsExactly("NEWS_PUBLISHED")
    }

    @Test
    fun `day context failure still returns the list`() {
        stubFind(items = listOf(item(1, 1L)), total = 1)
        every { repo.findDayContext(any(), any()) } throws RuntimeException("db")

        assertThat(service.getItems().items).hasSize(1)
    }

    @Test
    fun `quant signal filter restricts to the caller's accessible signal stocks`() {
        every { quant.stockIdsWithSignalsSince(7L, any()) } returns setOf(3L)
        every { repo.findItems(any(), any(), any(), setOf(3L), any()) } returns emptyList()
        every { repo.count(any(), setOf(3L), any()) } returns 0

        service.getItems(criteria = ScreenerCriteria(events = listOf(ScreenerEventFilter.QUANT_SIGNAL)), userId = 7L)

        verify { repo.findItems(any(), any(), any(), setOf(3L), any()) }
    }

    @Test
    fun `quant signal filter without login is rejected`() {
        assertThatThrownBy {
            service.getItems(criteria = ScreenerCriteria(events = listOf(ScreenerEventFilter.QUANT_SIGNAL)), userId = null)
        }.isInstanceOf(ScreenerLoginRequiredException::class.java)
    }

    @Test
    fun `cache key separates users only when the quant signal filter is used`() {
        val public = ScreenerCriteria(sectors = listOf("반도체"))
        val quantOnly = ScreenerCriteria(events = listOf(ScreenerEventFilter.QUANT_SIGNAL))

        assertThat(ScreenerService.cacheKey("realtime", public, 20, 0, 1L))
            .isEqualTo(ScreenerService.cacheKey("realtime", public, 20, 0, 2L))
        assertThat(ScreenerService.cacheKey("realtime", quantOnly, 20, 0, 1L))
            .isNotEqualTo(ScreenerService.cacheKey("realtime", quantOnly, 20, 0, 2L))
    }

    @Test
    fun `today starts at KST midnight`() {
        val t = ScreenerService.todayStart(java.time.Instant.parse("2026-10-05T16:30:00Z")) // 10-06 01:30 KST
        assertThat(t).isEqualTo(java.time.Instant.parse("2026-10-05T15:00:00Z"))
    }

    // ── search with universe filters (Quant Lab 종목 선택기용) ─────────────────────

    private fun stubEsResult(vararg ids: Long) {
        every { stockSearchService.search(any()) } returns ids.map { id ->
            com.monticker.api.stock.application.StockSearchResult(id, "00$id", "종목$id", "KOSPI", null, null)
        }
    }

    @Test
    fun `search excludes results outside the requested market`() {
        stubEsResult(1L, 2L)
        every { repo.findItemsByStockIds(listOf(1L, 2L), "amount") } returns listOf(
            item(1, 1L, market = "KOSPI"),
            item(2, 2L, market = "NASDAQ"),
        )

        val result = service.search(query = "삼성", market = "domestic")

        assertThat(result.items).extracting("stockId").containsExactly(1L)
    }

    @Test
    fun `search excludes results outside the requested market cap tier`() {
        stubEsResult(1L, 2L)
        every { repo.findItemsByStockIds(listOf(1L, 2L), "amount") } returns listOf(
            item(1, 1L, marketCap = 2_000_000_000_000L), // large
            item(2, 2L, marketCap = 50_000_000_000L),    // small
        )

        val result = service.search(query = "삼성", marketCapTier = "large")

        assertThat(result.items).extracting("stockId").containsExactly(1L)
    }

    @Test
    fun `search returns everything when market and marketCapTier are left as default`() {
        stubEsResult(1L, 2L)
        every { repo.findItemsByStockIds(listOf(1L, 2L), "amount") } returns listOf(
            item(1, 1L, market = "KOSPI", marketCap = 50_000_000_000L),
            item(2, 2L, market = "NASDAQ", marketCap = 2_000_000_000_000L),
        )

        val result = service.search(query = "삼성")

        assertThat(result.items).hasSize(2)
    }
}
