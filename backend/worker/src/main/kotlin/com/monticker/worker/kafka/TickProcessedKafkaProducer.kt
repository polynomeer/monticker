package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Properties

const val TICK_PROCESSED_TOPIC = "market.tick-processed"

/**
 * 이벤트 감지 완료 후 alert 워커에 전파하기 위해 market.tick-processed 토픽에 발행한다.
 * worker.role=event 일 때만 활성화된다.
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}' == 'event'")
class TickProcessedKafkaProducer(
    @Value("\${kafka.brokers:localhost:9092}") brokers: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    private val producer = KafkaProducer<String, String>(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        }
    )

    fun publish(stockId: Long, price: BigDecimal) {
        runCatching {
            val payload = objectMapper.writeValueAsString(TickProcessedMessage(stockId, price))
            producer.send(ProducerRecord(TICK_PROCESSED_TOPIC, stockId.toString(), payload))
        }.onFailure { log.warn("tick-processed Kafka 발행 실패: {}", it.message) }
    }
}
