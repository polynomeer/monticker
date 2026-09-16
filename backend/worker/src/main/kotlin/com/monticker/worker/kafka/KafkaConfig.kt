package com.monticker.worker.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor
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
    @Value("\${kafka.consumer.session-timeout-ms:10000}") private val sessionTimeoutMs: Int,
    @Value("\${kafka.consumer.heartbeat-interval-ms:3000}") private val heartbeatIntervalMs: Int,
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
            // M-002(b) / D-M2-03: 기본값(session.timeout 45s, eager 어사이너)에서는 worker 하나가 SIGKILL 되면 그 파티션이
            // 45.0s 멈추고, 대체 프로세스가 그 사이에 합류하면 살아남은 프로세스까지 세션 만료까지(관측 18.5s) 전 파티션을
            // 내려놓는다 — 시세 전체가 멈춘다. 10s/3s 는 Kafka 3.0 이전 기본값이고, cooperative-sticky 는 리밸런스 중에도
            // 자기 파티션을 계속 처리한다(3회 재현 후 수정, reports/M-002 §4.3).
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to sessionTimeoutMs,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to heartbeatIntervalMs,
            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG to listOf(CooperativeStickyAssignor::class.java.name),
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
