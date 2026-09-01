package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

const val TICK_PROCESSED_TOPIC = "market.tick-processed"

// Kafka 장애가 길어져도 힙이 무한정 자라지 않도록 발행 큐를 제한한다.
private const val PUBLISH_QUEUE_CAPACITY = 10_000

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

    // KafkaProducer#send는 콜백을 넘겨도 브로커 메타데이터를 못 받으면 max.block.ms까지
    // 호출 스레드를 동기 블로킹한다 — Event Detector 핫 패스 탈출: 호출 스레드를 블로킹하지 않음
    // 큐는 무제한이 아님 — Kafka가 오래 죽어 있으면 쌓이는 대신 가장 오래된 항목부터 버려서
    // 메모리 증가 대신 유실을 택한다 (틱 처리 결과는 최신 값만 의미 있음)
    private val publishExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(PUBLISH_QUEUE_CAPACITY),
        RejectedExecutionHandler { r, executor ->
            log.warn("tick-processed Kafka 발행 큐가 가득 차 가장 오래된 항목을 버립니다")
            executor.queue.poll()
            executor.execute(r)
        },
    )

    fun publish(stockId: Long, price: BigDecimal) {
        try {
            publishExecutor.submit {
                runCatching {
                    val payload = objectMapper.writeValueAsString(TickProcessedMessage(stockId, price))
                    producer.send(ProducerRecord(TICK_PROCESSED_TOPIC, stockId.toString(), payload))
                }.onFailure { log.warn("tick-processed Kafka 발행 실패: {}", it.message) }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("tick-processed Kafka 발행 큐 종료됨 — 항목을 버립니다: {}", e.message)
        }
    }

    @PreDestroy
    fun shutdown() {
        publishExecutor.shutdown()
    }
}
