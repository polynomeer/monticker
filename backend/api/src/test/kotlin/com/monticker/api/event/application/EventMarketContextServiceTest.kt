package com.monticker.api.event.application

import com.monticker.api.event.domain.EventType
import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.EventCandleWindow
import com.monticker.api.event.infrastructure.EventMarketContextRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class EventMarketContextServiceTest {

    private val repo = mockk<EventMarketContextRepository>()
    private val service = EventMarketContextService(repo)

    private fun window(base: String?, end: String?, ev: Double?, baseline: Double?) =
        EventCandleWindow(1L, base?.let(::BigDecimal), end?.let(::BigDecimal), ev, baseline)

    @Test
    fun `computes window change and volume multiple`() {
        val ctx = EventMarketContextService.compute(window("10000", "10350", 3000.0, 1000.0))

        assertThat(ctx.windowChangePct).isCloseTo(3.5, Offset.offset(1e-9))
        assertThat(ctx.volumeMultiple).isCloseTo(3.0, Offset.offset(1e-9))
    }

    @Test
    fun `missing candles yield nulls instead of zeros`() {
        val ctx = EventMarketContextService.compute(window(null, "10350", null, 1000.0))

        assertThat(ctx.windowChangePct).isNull()
        assertThat(ctx.volumeMultiple).isNull()
    }

    @Test
    fun `tiny baseline volume does not produce a huge multiple`() {
        val ctx = EventMarketContextService.compute(window("100", "100", 5000.0, 0.2))

        assertThat(ctx.volumeMultiple).isNull()
        assertThat(ctx.windowChangePct).isEqualTo(0.0)
    }

    @Test
    fun `repository failure does not break the feed`() {
        every { repo.findWindows(any(), any()) } throws RuntimeException("db down")
        val event = StockEvent(id = 7, stockId = 1, eventType = EventType.NEWS_PUBLISHED, title = "t", eventTime = Instant.now())

        assertThat(service.contextFor(listOf(event))).isEmpty()
    }

    @Test
    fun `maps contexts by event id`() {
        every { repo.findWindows(any(), any()) } returns listOf(EventCandleWindow(7, BigDecimal("100"), BigDecimal("90"), 10.0, 5.0))
        val event = StockEvent(id = 7, stockId = 1, eventType = EventType.PRICE_DROP, title = "t", eventTime = Instant.now())

        val ctx = service.contextFor(listOf(event))[7]!!

        assertThat(ctx.windowChangePct).isCloseTo(-10.0, Offset.offset(1e-9))
        assertThat(ctx.volumeMultiple).isCloseTo(2.0, Offset.offset(1e-9))
    }
}
