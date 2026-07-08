package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.AlertEvaluator
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * market.tick-processed 토픽을 소비해 알림 규칙을 평가한다.
 * worker.role=alert 일 때만 활성화된다.
 * role=all 에서는 AlertEvaluator 의 @EventListener 가 같은 역할을 한다.
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}' == 'alert'")
class AlertKafkaConsumer(
    private val alertEvaluator: AlertEvaluator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @KafkaListener(topics = [TICK_PROCESSED_TOPIC], groupId = "monticker-alert-worker")
    fun onTickProcessed(record: ConsumerRecord<String, String>) {
        try {
            val msg = objectMapper.readValue(record.value(), TickProcessedMessage::class.java)
            alertEvaluator.processAlert(msg.stockId, msg.price)
        } catch (e: Exception) {
            log.error("알림 Kafka 처리 실패 (key={}): {}", record.key(), e.message)
        }
    }
}
