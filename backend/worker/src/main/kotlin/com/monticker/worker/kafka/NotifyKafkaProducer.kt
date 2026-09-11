package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.AlertRuleRow
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Properties

const val NOTIFY_TOPIC = "notify.commands"

/** 발동한 알림 (평가 → 발송 사이의 메시지). 발송 워커가 필요한 모든 것을 담는다 — DB를 다시 읽지 않도록. */
data class AlertTriggeredMessage(
    val ruleId: Long, val userId: Long, val stockId: Long, val ruleType: String, val conditionJson: String,
    val price: BigDecimal,
) {
    fun toRow() = AlertRuleRow(ruleId, userId, stockId, ruleType, conditionJson)
}

/**
 * ADR-044 §Decision 4 — 평가와 발송 분리. 발동은 notify.commands로 보내고 평가 스레드는 여기서 끝난다.
 * Expo/SMTP/ES가 느려져도 틱 파이프라인이 밀리지 않는다. 키 = ruleId (같은 룰의 순서 보존).
 * 토픽은 ADR-040 KafkaTopicConfig(api)가 선언한다.
 */
@Component
@ConditionalOnProperty(name = ["alert.dispatch-mode"], havingValue = "kafka", matchIfMissing = true)
class NotifyKafkaProducer(
    @Value("\${kafka.brokers:localhost:9092}") brokers: String,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val failed = meterRegistry.counter("alert_notify_publish_failed_total")
    private val producer = KafkaProducer<String, String>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000)   // 브로커 부재가 평가 스레드를 오래 잡지 않게
    })

    fun publish(rule: AlertRuleRow, price: BigDecimal) {
        val msg = AlertTriggeredMessage(rule.id, rule.userId, rule.stockId, rule.ruleType, rule.conditionJson, price)
        producer.send(ProducerRecord(NOTIFY_TOPIC, rule.id.toString(), objectMapper.writeValueAsString(msg))) { _, ex ->
            if (ex != null) { failed.increment(); log.warn("[NotifyKafkaProducer] 발행 실패 ruleId={}: {}", rule.id, ex.message) }
        }
    }
}
