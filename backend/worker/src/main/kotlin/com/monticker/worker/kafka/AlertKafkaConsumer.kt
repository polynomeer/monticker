package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.AlertEvaluator
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.retry.annotation.Backoff
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

    @RetryableTopic(
        attempts = "3",
        backoff = Backoff(delay = 1_000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "false",
    )
    @KafkaListener(topics = [TICK_PROCESSED_TOPIC], groupId = "monticker-alert-worker")
    fun onTickProcessed(record: ConsumerRecord<String, String>) {
        val msg = objectMapper.readValue(record.value(), TickProcessedMessage::class.java)
        alertEvaluator.processAlert(msg.stockId, msg.price)
    }

    @DltHandler
    fun onTickProcessedDlt(record: ConsumerRecord<String, String>) {
        log.error(
            "[DLT] market.tick-processed 최종 실패 — 알림 평가 누락. " +
            "topic={} partition={} offset={} key={}",
            record.topic(), record.partition(), record.offset(), record.key(),
        )
    }
}
