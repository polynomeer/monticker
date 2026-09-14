package com.monticker.api.marketdata.infrastructure

import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * ADR-039 — worker가 1초마다 발행하는 market.summary를 /topic/market/summary 로 릴레이한다.
 * 페이로드는 이미 JSON 문자열이라 파싱하지 않고 그대로 보낸다. 모든 api pod가 받아야 하므로
 * ADR-038과 같은 전 파티션 할당이다(파티션 1개지만 원칙은 같다).
 */
@Component
class MarketSummaryBroadcastConsumer(
    private val messagingTemplate: SimpMessagingTemplate,
    consumerFactory: ConsumerFactory<String, String>,
    kafkaAdmin: KafkaAdmin,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {

    companion object {
        const val TOPIC = "market.summary"
        const val DESTINATION = "/topic/market/summary"
    }

    private val relayed = meterRegistry.counter("ws_market_summary_relayed_total")
    private val listener = AllPartitionsListener(TOPIC, consumerFactory, kafkaAdmin, ::onSummary)

    fun onSummary(record: ConsumerRecord<String, String>) {
        messagingTemplate.convertAndSend(DESTINATION, record.value())
        relayed.increment()
    }

    override fun start() = listener.start()
    override fun stop() = listener.stop()
    override fun isRunning(): Boolean = listener.isRunning()
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = Int.MAX_VALUE - 100
}
