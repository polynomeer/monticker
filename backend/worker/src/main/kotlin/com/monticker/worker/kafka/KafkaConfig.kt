package com.monticker.worker.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.MicrometerConsumerListener

/**
 * worker의 @KafkaListener 컨테이너 팩토리 — **항상** 활성. 배경은 ADR-005.
 *
 * 이전엔 ingestion.source=kafka 일 때만 켜졌지만 @KafkaListener(틱·알림·발송)는 조건 없이 늘 살아 있었고, internal
 * 모드에서는 Boot 자동구성 팩토리를 썼다. ADR-042로 Modulith(spring-modulith-events-kafka)가 들어오자 그 자동구성
 * 팩토리가 Modulith의 ByteArrayJsonMessageConverter 빈을 RecordMessageConverter로 주입받아 **String 레코드가
 * byte[]로 바뀌어 모든 틱 컨슈머가 ClassCastException으로 죽었다**(라이브 검증, 로그 100만 줄). 우리 팩토리는
 * 컨버터 없이 String 역직렬화기만 쓰므로 무조건 이걸 써야 한다 — 조건을 없앴다. 덤으로 internal 모드에서도
 * 컨슈머 메트릭(MicrometerConsumerListener)과 동시성 설정이 적용된다.
 */
@Configuration
@EnableKafka
class KafkaConfig(
    @Value("\${kafka.brokers:localhost:9092}") private val brokers: String,
    @Value("\${spring.kafka.listener.concurrency:1}") private val concurrency: Int,
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
        // 파티션 수(ADR-040)까지 병렬 소비. 종목별 상태(CandleAggregator, 감지기 EMA)는 키=stockId라
        // 같은 종목이 항상 같은 파티션/스레드로 오므로 스레드 간 공유가 없다.
        factory.setConcurrency(concurrency)
        return factory
    }
}
