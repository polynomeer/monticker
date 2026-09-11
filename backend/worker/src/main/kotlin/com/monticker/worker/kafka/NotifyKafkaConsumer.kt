package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.AlertDispatcher
import io.micrometer.core.instrument.MeterRegistry
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
 * ADR-044 — notify.commands를 소비해 실제 발송(AlertDispatcher)을 한다.
 * role=notify(전용 발송 프로세스) | alert | all 에서 활성화. 푸시/메일 실패는 재시도 후 DLT.
 * 쿨다운(Redis SETNX 10분)이 AlertDispatcher 안에 있으므로 재시도로 중복 발송되지 않는다.
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}'.matches('notify|alert|all') && '\${alert.dispatch-mode:kafka}' == 'kafka'")
class NotifyKafkaConsumer(
    private val dispatcher: AlertDispatcher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @RetryableTopic(
        attempts = "3",
        backoff = Backoff(delay = 2_000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "false",
    )
    @KafkaListener(topics = [NOTIFY_TOPIC], groupId = "monticker-notify")
    fun onTriggered(record: ConsumerRecord<String, String>) {
        val msg = objectMapper.readValue(record.value(), AlertTriggeredMessage::class.java)
        dispatcher.dispatch(msg.toRow(), msg.price)
    }

    @DltHandler
    fun onDlt(record: ConsumerRecord<String, String>) {
        meterRegistry.counter("dlt_messages_total", "topic", NOTIFY_TOPIC).increment()
        log.error("[DLT] notify.commands 최종 실패 — 알림 미발송. key={} offset={}", record.key(), record.offset())
    }
}
