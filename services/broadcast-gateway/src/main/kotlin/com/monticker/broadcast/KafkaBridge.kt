package com.monticker.broadcast

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * market.ticks / market.events 토픽을 구독해 BroadcastServer로 중계한다.
 * 별도 스레드에서 poll 루프를 돈다 — Netty의 이벤트 루프와는 분리된 스레드다.
 */
class KafkaBridge(
    private val brokers: String,
    private val server: BroadcastServer,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(true)

    fun run() {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "monticker-broadcast-gateway")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        }

        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf("market.ticks", "market.events"))
            log.info("KafkaBridge subscribed to market.ticks, market.events (brokers={})", brokers)

            while (running.get()) {
                val records = consumer.poll(Duration.ofMillis(200))
                for (record in records) {
                    server.broadcast(record.key() ?: "ALL", record.value())
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }
}
