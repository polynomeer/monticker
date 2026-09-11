package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.PriceTick
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.Instant

/** ADR-038 conflation — 같은 flush 주기 안의 같은 종목 틱은 마지막 것만 나간다. */
class PriceBroadcasterTest {

    private val template = mockk<SimpMessagingTemplate>(relaxed = true)
    private val registry = SimpleMeterRegistry()
    private val broadcaster = PriceBroadcaster(template, registry)

    private fun tick(stockId: Long, price: Long) =
        PriceTick(stockId, "S$stockId", BigDecimal.valueOf(price), 1, Instant.now())

    @Test
    fun `broadcast는 즉시 발행하지 않고 버퍼에 쌓는다`() {
        broadcaster.broadcast(tick(1, 100))
        verify(exactly = 0) { template.convertAndSend(any<String>(), any<Any>()) }
        assertThat(broadcaster.pending()).isEqualTo(1)
    }

    @Test
    fun `같은 종목의 틱 20개가 한 주기에 오면 flush는 마지막 값 1건만 보낸다`() {
        repeat(20) { i -> broadcaster.broadcast(tick(1, 100 + i.toLong())) }
        broadcaster.flush()

        val payloads = mutableListOf<Any>()
        verify(exactly = 1) { template.convertAndSend("/topic/stocks/1", capture(payloads)) }
        @Suppress("UNCHECKED_CAST")
        assertThat((payloads.single() as Map<String, Any>)["price"]).isEqualTo(BigDecimal.valueOf(119))
        assertThat(registry.counter("ws_broadcast_ticks_in_total").count()).isEqualTo(20.0)
        assertThat(registry.counter("ws_broadcast_messages_out_total").count()).isEqualTo(1.0)
        assertThat(broadcaster.pending()).isZero()
    }

    @Test
    fun `다른 종목은 각각 나간다`() {
        broadcaster.broadcast(tick(1, 100)); broadcaster.broadcast(tick(2, 200)); broadcaster.broadcast(tick(3, 300))
        broadcaster.flush()
        verify(exactly = 1) { template.convertAndSend("/topic/stocks/1", any<Any>()) }
        verify(exactly = 1) { template.convertAndSend("/topic/stocks/2", any<Any>()) }
        verify(exactly = 1) { template.convertAndSend("/topic/stocks/3", any<Any>()) }
    }

    @Test
    fun `버퍼가 비어 있으면 flush는 아무것도 보내지 않는다`() {
        broadcaster.flush()
        verify(exactly = 0) { template.convertAndSend(any<String>(), any<Any>()) }
    }
}
