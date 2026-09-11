package com.monticker.worker.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.MicrometerConsumerListener

/**
 * 활성화 조건: ingestion.source=kafka (기본값 internal — 기존 MockPriceGenerator 경로 유지).
 * 자세한 배경은 ADR-005 참고.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = ["ingestion.source"], havingValue = "kafka")
class KafkaConfig(
    @Value("\${kafka.brokers:localhost:9092}") private val brokers: String,
    private val meterRegistry: MeterRegistry,
) {
    @Bean
    fun consumerFactory(): ConsumerFactory<String, String> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers,
            ConsumerConfig.GROUP_ID_CONFIG to "monticker-worker",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
        )
        // Spring Boot 자동구성 팩토리는 컨슈머 메트릭(records_lag_max 등)을 자동으로 붙이지만
        // 이 커스텀 팩토리는 그렇지 않다 — 컨슈머 랙 알람(TickPipelineStalled)이 여기에 의존한다 (P1-2).
        return DefaultKafkaConsumerFactory<String, String>(props).apply {
            addListener(MicrometerConsumerListener(meterRegistry))
        }
    }

    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory()
        return factory
    }
}
