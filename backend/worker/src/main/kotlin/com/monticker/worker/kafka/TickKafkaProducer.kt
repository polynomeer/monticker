package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.marketdata.GeneratedTick
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

const val TICKS_TOPIC = "market.ticks"

// Kafka 장애가 길어져도 힙이 무한정 자라지 않도록 발행 큐를 제한한다.
private const val PUBLISH_QUEUE_CAPACITY = 10_000

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
    // 큐는 무제한이 아님 — Kafka가 오래 죽어 있으면 쌓이는 대신 가장 오래된 틱부터 버려서
    // 메모리 증가 대신 유실을 택한다 (틱은 최신 값만 의미 있음)
    private val publishExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(PUBLISH_QUEUE_CAPACITY),
        RejectedExecutionHandler { r, executor ->
            log.warn("틱 Kafka 발행 큐가 가득 차 가장 오래된 틱을 버립니다")
            executor.queue.poll()
            executor.execute(r)
        },
    )

    fun publish(tick: GeneratedTick) {
        try {
            publishExecutor.submit {
                runCatching {
                    val payload = objectMapper.writeValueAsString(tick)
                    producer.send(ProducerRecord(TICKS_TOPIC, tick.stockId.toString(), payload))
                }.onFailure { log.warn("틱 Kafka 발행 실패: {}", it.message) }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("틱 Kafka 발행 큐 종료됨 — 틱을 버립니다: {}", e.message)
        }
    }

    @PreDestroy
    fun shutdown() {
        publishExecutor.shutdown()
    }
}
