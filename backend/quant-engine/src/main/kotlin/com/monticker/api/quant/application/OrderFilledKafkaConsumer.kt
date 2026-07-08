package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.events.OrderFilledEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * trading-service가 발행하는 trading.order-filled 토픽을 소비한다.
 *
 * MSA 모드에서 OrderFilledStrategyListener(Spring Event 방식)를 대체한다.
 * quant.trading-events.enabled=true 일 때만 활성화 (MSA 모드 구분).
 */
@Component
@ConditionalOnProperty(name = ["quant.trading-events.enabled"], havingValue = "true")
class OrderFilledKafkaConsumer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @KafkaListener(topics = ["trading.order-filled"], groupId = "monticker-quant-engine")
    fun onOrderFilled(record: ConsumerRecord<String, String>) {
        try {
            val event = objectMapper.readValue(record.value(), OrderFilledEvent::class.java)
            log.info("[Quant] OrderFilled (Kafka): userId={} stockId={} side={} qty={} price={}",
                event.userId, event.stockId, event.side, event.quantity, event.fillPrice)
            // 향후 RuleSet 실전 검증(live tracking) 로직 추가 지점
        } catch (e: Exception) {
            log.error("[Quant] trading.order-filled 처리 실패 (key={}): {}", record.key(), e.message)
        }
    }
}
