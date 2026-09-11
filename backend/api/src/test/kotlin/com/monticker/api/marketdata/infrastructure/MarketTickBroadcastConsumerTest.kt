package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.support.TopicPartitionOffset

/** ADR-038 — 컨슈머 그룹 분할 대신 전 파티션 수동 할당. */
class MarketTickBroadcastConsumerTest {

    @Test
    fun `파티션 n개 전부를 END 위치로 할당한다 — 하나라도 빠지면 그 파티션의 종목은 어떤 클라이언트도 못 받는다`() {
        val a = MarketTickBroadcastConsumer.assignmentsFor(256)
        assertThat(a).hasSize(256)
        assertThat(a.map { it.partition }).containsExactlyElementsOf(0 until 256)
        assertThat(a.map { it.topic }.toSet()).containsExactly("market.ticks")
        assertThat(a.map { it.position }.toSet()).containsExactly(TopicPartitionOffset.SeekPosition.END)
    }

    @Test
    fun `틱 1건은 브로드캐스터 버퍼와 조건부 주문 이벤트 양쪽으로 간다`() {
        val broadcaster = mockk<PriceBroadcaster>(relaxed = true)
        val events = mockk<ApplicationEventPublisher>(relaxed = true)
        val registry = SimpleMeterRegistry()
        val consumer = MarketTickBroadcastConsumer(broadcaster, events, mockk<ConsumerFactory<String, String>>(), mockk<KafkaAdmin>(), registry)

        consumer.onTick(ConsumerRecord("market.ticks", 0, 0L, "2",
            """{"stockId":2,"symbol":"005930","market":"KOSPI","price":77000,"volume":10,"tradeTime":"2026-09-11T05:00:00Z"}"""))

        verify(exactly = 1) { broadcaster.broadcast(match { it.stockId == 2L && it.symbol == "005930" }) }
        verify(exactly = 1) { events.publishEvent(any<MarketTickReceivedEvent>()) }
        assertThat(registry.counter("tick_broadcast_failed_total").count()).isZero()
    }

    @Test
    fun `깨진 틱은 삼키되 실패 카운터를 올린다 — 다음 틱이 곧 온다`() {
        val registry = SimpleMeterRegistry()
        val consumer = MarketTickBroadcastConsumer(mockk(relaxed = true), mockk(relaxed = true), mockk(), mockk(), registry)

        consumer.onTick(ConsumerRecord("market.ticks", 0, 0L, "x", "not json"))

        assertThat(registry.counter("tick_broadcast_failed_total").count()).isEqualTo(1.0)
    }
}
