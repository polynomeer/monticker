package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.notification.UserNotificationDispatcher
import com.monticker.worker.notification.UserNotificationMessage
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

const val NOTIFY_USER_TOPIC = "notify.user"

/**
 * ADR-065 — api가 아웃박스로 보낸 사용자 알림(notify.user)을 소비해 발송한다. 알림 규칙 발송(NotifyKafkaConsumer)과 같은
 * 역할(notify|alert|all)에서 돈다. `alert.dispatch-mode`는 알림 규칙 경로의 스위치라 여기엔 걸지 않는다 — api에는 이 토픽
 * 말고 다른 발송 경로가 없다. 재시도 토픽은 api KafkaTopicConfig가 만든다(attempts 이중 관리).
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}'.matches('notify|alert|all')")
class UserNotifyKafkaConsumer(
    private val dispatcher: UserNotificationDispatcher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    init { meterRegistry.counter("dlt_messages_total", "topic", NOTIFY_USER_TOPIC) }   // 0으로 미리 등록 — DltMessagesGrowing이 처음부터 검증 가능
    // 모르는 필드는 무시한다 — api가 와이어에 필드를 더해도(ADR-082 category처럼) 이 소비자가 메시지를 DLT로 버리지 않게.
    private val objectMapper = ObjectMapper().findAndRegisterModules()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @RetryableTopic(
        attempts = "3",
        backoff = Backoff(delay = 2_000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "false",
    )
    @KafkaListener(topics = [NOTIFY_USER_TOPIC], groupId = "monticker-notify-user")
    fun onCommand(record: ConsumerRecord<String, String>) {
        dispatcher.dispatch(objectMapper.readValue(record.value(), UserNotificationMessage::class.java))
    }

    @DltHandler
    fun onDlt(record: ConsumerRecord<String, String>) {
        meterRegistry.counter("dlt_messages_total", "topic", NOTIFY_USER_TOPIC).increment()
        log.error("[DLT] notify.user 최종 실패 — 사용자 알림 미발송. key={} offset={}", record.key(), record.offset())
    }
}
