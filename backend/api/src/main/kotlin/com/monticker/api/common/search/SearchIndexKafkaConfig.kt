package com.monticker.api.common.search

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries

/**
 * ADR-042 — search.index 배치 컨슈머의 컨테이너.
 * - 배치 리스너 + DefaultErrorHandler: 배치가 예외를 던지면 2s → 6s → 18s 백오프로 3회 재시도(총 4회) 후
 *   각 레코드를 `search.index-dlt`로 보낸다(KafkaTopicConfig.RetryFamilies가 -dlt 토픽을 선언한다).
 * - auto.offset.reset=earliest: 앱 기본(latest, ADR-029 브로드캐스트용)을 쓰면 그룹이 처음 만들어질 때
 *   그 전에 외부화된 이벤트를 건너뛴다 — 색인 이벤트는 하나도 버리면 안 된다.
 * - DLT 발행용 템플릿은 String 직렬화기: 앱 프로듀서는 Modulith 외부화 때문에 ByteArraySerializer라
 *   컨슈머 레코드(String)를 그대로 넘기면 거부된다. **빈으로 노출하지 않는다** — KafkaTemplate 빈이 하나 더
 *   생기면 Boot 자동구성 템플릿이 물러나고 Modulith 외부화가 이 템플릿(컨버터 없음)을 잡아 전부 실패한다
 *   (라이브 검증에서 겪음).
 */
@Configuration
class SearchIndexKafkaConfig(private val props: KafkaProperties) {

    private fun dltTemplate(): KafkaTemplate<Any, Any> {
        val cfg = props.buildProducerProperties(null).toMutableMap()
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(cfg))
    }

    @Bean
    fun searchIndexContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
        val cfg = props.buildConsumerProperties(null).toMutableMap()
        cfg[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        cfg[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        cfg[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        cfg[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = 1000          // 벌크 상한 (ADR-042: 최대 1,000건)
        cfg[ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG] = 1000         // 배치 대기 상한 1초

        val recoverer = DeadLetterPublishingRecoverer(dltTemplate()) { record, _ ->
            TopicPartition(record.topic() + "-dlt", record.partition())
        }
        val backoff = ExponentialBackOffWithMaxRetries(3).apply { initialInterval = 2_000; multiplier = 3.0 }

        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            consumerFactory = DefaultKafkaConsumerFactory(cfg)
            isBatchListener = true
            setCommonErrorHandler(DefaultErrorHandler(recoverer, backoff).apply { setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR) })
        }
    }
}
