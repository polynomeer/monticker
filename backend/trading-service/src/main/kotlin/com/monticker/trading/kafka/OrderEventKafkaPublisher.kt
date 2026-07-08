package com.monticker.trading.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.Properties

const val ORDER_FILLED_TOPIC    = "trading.order-filled"
const val ORDER_CANCELLED_TOPIC = "trading.order-cancelled"

/**
 * 체결/취소 이벤트를 matching 트랜잭션 커밋 후 Kafka로 발행한다.
 *
 * @TransactionalEventListener(AFTER_COMMIT): 트랜잭션이 롤백되면 발행되지 않는다.
 * quant-engine 등 외부 서비스가 이 토픽을 구독해 교차 서비스 연동을 수행한다.
 */
@Component
class OrderEventKafkaPublisher(
    @Value("\${kafka.brokers:localhost:9092}") brokers: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    private val producer = KafkaProducer<String, String>(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            // 브로커 장애 시 손실 최소화
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.RETRIES_CONFIG, 3)
        }
    )

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderFilled(event: OrderFilledEvent) {
        runCatching {
            val payload = objectMapper.writeValueAsString(event)
            producer.send(ProducerRecord(ORDER_FILLED_TOPIC, event.orderId.toString(), payload))
            log.info("[Trading] order-filled published: orderId={} userId={}", event.orderId, event.userId)
        }.onFailure {
            log.warn("[Trading] order-filled Kafka 발행 실패 (orderId={}): {}", event.orderId, it.message)
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderCancelled(event: OrderCancelledEvent) {
        runCatching {
            val payload = objectMapper.writeValueAsString(event)
            producer.send(ProducerRecord(ORDER_CANCELLED_TOPIC, event.orderId.toString(), payload))
            log.info("[Trading] order-cancelled published: orderId={} userId={}", event.orderId, event.userId)
        }.onFailure {
            log.warn("[Trading] order-cancelled Kafka 발행 실패 (orderId={}): {}", event.orderId, it.message)
        }
    }
}
