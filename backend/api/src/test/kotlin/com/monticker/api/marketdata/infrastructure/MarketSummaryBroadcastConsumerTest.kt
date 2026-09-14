package com.monticker.api.marketdata.infrastructure

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class MarketSummaryBroadcastConsumerTest {
    @Test
    fun `요약 JSON을 파싱 없이 topic market summary 로 릴레이한다`() {
        val template = mockk<SimpMessagingTemplate>(relaxed = true)
        val consumer = MarketSummaryBroadcastConsumer(template, mockk(), mockk(), SimpleMeterRegistry())
        consumer.onSummary(ConsumerRecord("market.summary", 0, 0L, "summary", """{"advancers":3}"""))
        verify(exactly = 1) { template.convertAndSend("/topic/market/summary", """{"advancers":3}""") }
    }
}
