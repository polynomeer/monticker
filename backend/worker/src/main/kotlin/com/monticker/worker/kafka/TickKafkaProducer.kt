package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.marketdata.GeneratedTick
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.util.Properties

const val TICKS_TOPIC = "market.ticks"

/**
 * Spring 기반 틱 생성기(MockPriceGenerator)가 발행하는 Kafka 프로듀서.
 * worker.role=market 일 때만 활성화된다.
 * Go market-gateway가 올라오면 이 Bean은 비활성화하고 gateway가 직접 발행한다.
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}' == 'market'")
class TickKafkaProducer(
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

    fun publish(tick: GeneratedTick) {
        runCatching {
            val payload = objectMapper.writeValueAsString(tick)
            producer.send(ProducerRecord(TICKS_TOPIC, tick.stockId.toString(), payload))
        }.onFailure { log.warn("틱 Kafka 발행 실패: {}", it.message) }
    }
}
