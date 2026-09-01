package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.detector.DetectedEvent
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

const val EVENTS_TOPIC = "market.events"

// Kafka 장애가 길어져도 힙이 무한정 자라지 않도록 발행 큐를 제한한다.
private const val PUBLISH_QUEUE_CAPACITY = 10_000

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
    // 큐는 무제한이 아님 — Kafka가 오래 죽어 있으면 쌓이는 대신 가장 오래된 이벤트부터 버려서
    // 메모리 증가 대신 유실을 택한다
    private val publishExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(PUBLISH_QUEUE_CAPACITY),
        RejectedExecutionHandler { r, executor ->
            log.warn("Kafka 이벤트 발행 큐가 가득 차 가장 오래된 이벤트를 버립니다")
            executor.queue.poll()
            executor.execute(r)
        },
    )

    fun publish(event: DetectedEvent) {
        try {
            publishExecutor.submit {
                runCatching {
                    val payload = objectMapper.writeValueAsString(event)
                    producer.send(ProducerRecord(EVENTS_TOPIC, event.stockId.toString(), payload))
                }.onFailure { log.warn("Kafka 이벤트 발행 실패: {}", it.message) }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("Kafka 이벤트 발행 큐 종료됨 — 이벤트를 버립니다: {}", e.message)
        }
    }

    @PreDestroy
    fun shutdown() {
        publishExecutor.shutdown()
    }
}
