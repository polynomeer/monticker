package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.detector.DetectedEvent
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.concurrent.Executors

const val EVENTS_TOPIC = "market.events"

/**
 * 탐지된 이벤트를 Kafka로 발행한다 — Netty 브로드캐스트 게이트웨이가 이를 구독해
 * WebSocket 클라이언트에 푸시한다. ingestion.source=kafka일 때만 활성화된다.
 */
@Component
@ConditionalOnProperty(name = ["ingestion.source"], havingValue = "kafka")
class EventKafkaProducer(
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

    // KafkaProducer#send는 콜백을 넘겨도 브로커 메타데이터를 못 받으면 max.block.ms까지
    // 호출 스레드를 동기 블로킹한다 — Event Detector 핫 패스 탈출: 호출 스레드를 블로킹하지 않음
    private val publishExecutor = Executors.newSingleThreadExecutor()

    fun publish(event: DetectedEvent) {
        publishExecutor.submit {
            runCatching {
                val payload = objectMapper.writeValueAsString(event)
                producer.send(ProducerRecord(EVENTS_TOPIC, event.stockId.toString(), payload))
            }.onFailure { log.warn("Kafka 이벤트 발행 실패: {}", it.message) }
        }
    }
}
