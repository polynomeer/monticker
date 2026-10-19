package com.monticker.api.portfolio.application

import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.domain.EventType
import com.monticker.api.event.domain.StockEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant

/** ADR-108 보유 종목 피드 — 보유가 없으면 이벤트를 읽지 않고, 한 건 더 읽어 다음 커서를 정하고, 커서를 그대로 되읽는다 */
class PortfolioEventFeedServiceTest {

    private val holdings = mockk<PortfolioHoldingsQueryService>()
    private val events = mockk<EventTimelineService>()
    private val jdbc = mockk<JdbcTemplate> {
        every { query(any<String>(), any<RowMapper<Pair<Long, Pair<String, String>>>>(), *anyVararg()) } returns
            listOf(1L to ("005930" to "삼성전자"))
    }
    private val service = PortfolioEventFeedService(holdings, events, jdbc)

    private fun event(id: Long, at: String) = StockEvent(
        id = id, stockId = 1L, eventType = EventType.PRICE_SPIKE, title = "급등 $id", eventTime = Instant.parse(at), importanceScore = 7,
    )

    @Test
    fun `보유 종목이 없으면 이벤트를 읽지 않는다`() {
        every { holdings.heldStockIds(9L) } returns emptySet()

        val feed = service.feed(9L, null, 20)

        assertThat(feed.heldStockCount).isZero()
        assertThat(feed.events).isEmpty()
        verify(exactly = 0) { events.getRecentForStocks(any(), any(), any(), any()) }
    }

    @Test
    fun `한 건 더 있으면 마지막으로 보인 이벤트의 시각과 id가 다음 커서다`() {
        every { holdings.heldStockIds(9L) } returns setOf(1L)
        every { events.getRecentForStocks(setOf(1L), any(), Long.MAX_VALUE, 3) } returns listOf(
            event(30, "2026-10-19T01:00:00.123456Z"), event(20, "2026-10-19T01:00:00.123456Z"), event(10, "2026-10-18T01:00:00Z"),
        )

        val feed = service.feed(9L, null, 2)

        assertThat(feed.events.map { it.id }).containsExactly(30L, 20L)
        assertThat(feed.events.first().name).isEqualTo("삼성전자")
        assertThat(feed.nextCursor).isEqualTo("2026-10-19T01:00:00.123456Z_20")
        assertThat(feed.heldStockCount).isEqualTo(1)
    }

    @Test
    fun `커서를 되받으면 그 시각과 id로 이어 읽고, 끝이면 커서가 없다`() {
        every { holdings.heldStockIds(9L) } returns setOf(1L)
        every { events.getRecentForStocks(setOf(1L), Instant.parse("2026-10-19T01:00:00.123456Z"), 20L, 3) } returns
            listOf(event(10, "2026-10-18T01:00:00Z"))

        val feed = service.feed(9L, "2026-10-19T01:00:00.123456Z_20", 2)

        assertThat(feed.events.map { it.id }).containsExactly(10L)
        assertThat(feed.nextCursor).isNull()
    }

    @Test
    fun `깨진 커서는 400(IllegalArgumentException)이다`() {
        every { holdings.heldStockIds(9L) } returns setOf(1L)
        listOf("abc", "2026-10-19_x", "_1").forEach {
            assertThatThrownBy { service.feed(9L, it, 2) }.`as`(it).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
