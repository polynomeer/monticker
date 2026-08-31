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
import java.util.concurrent.Executors

const val TICKS_TOPIC = "market.ticks"

/**
 * Spring 기반 틱 생성기(MockPriceGenerator)가 발행하는 Kafka 프로듀서.
 *
 * role=market : MarketTickScheduler 가 호출한다.
 * role=all    : MarketTickScheduler 가 호출한다. ingestion.source=kafka 일 때는
 *               Go market-gateway가 대신 발행하므로 자동으로 스케줄러 자체가 비활성화됨.
 *               (TickKafkaProducer Bean은 남아있어도 아무도 호출 안 함)
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}'.matches('market|all')")
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

    // KafkaProducer#send는 콜백을 넘겨도 브로커 메타데이터를 못 받으면 max.block.ms까지
    // 호출 스레드를 동기 블로킹한다 — MarketTickScheduler는 @Scheduled 스레드 풀을 쓰므로
    // hot path 탈출: collect() 스레드를 블로킹하지 않음
    private val publishExecutor = Executors.newSingleThreadExecutor()

    fun publish(tick: GeneratedTick) {
        publishExecutor.submit {
            runCatching {
                val payload = objectMapper.writeValueAsString(tick)
                producer.send(ProducerRecord(TICKS_TOPIC, tick.stockId.toString(), payload))
            }.onFailure { log.warn("틱 Kafka 발행 실패: {}", it.message) }
        }
    }
}
