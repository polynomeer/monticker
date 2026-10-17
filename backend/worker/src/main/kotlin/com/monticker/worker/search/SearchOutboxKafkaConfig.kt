package com.monticker.worker.search

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicBeanNames
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import tools.jackson.databind.json.JsonMapper

/**
 * ADR-042 §2 — worker에는 KafkaTemplate이 두 개 필요하다.
 *
 * - `defaultRetryTopicKafkaTemplate`(String/String): `@RetryableTopic`(틱·알림·발송 컨슈머)이 재시도 토픽·DLT로 레코드를
 *   전달할 때 쓴다. Spring Kafka가 이 **이름**으로 먼저 찾는다(`RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME`). 레코드 값이 String이라 직렬화기도 String이어야 한다 — 이걸 ByteArraySerializer로 바꿨더니
 *   market.ticks 컨슈머의 재시도 전달이 전부 실패하며 133,000줄의 에러가 났다(라이브 검증).
 * - `modulithKafkaTemplate`(Object/Object, **@Primary**): Modulith 외부화 전용. ByteArrayJacksonJsonMessageConverter
 *   (Boot의 Jackson 3 매퍼, ADR-104)가 이벤트를 byte[] JSON으로 만들므로 ByteArraySerializer + 짧은 delivery timeout
 *   (브로커 장애 시 스레드를 10초 안에 놓고 Outbox 재전송에 맡긴다 — CH-05).
 *
 * Modulith 1.x는 `KafkaOperations<Object, Object>`로 주입받아 제네릭으로 구분됐다. **2.x는 `KafkaOperations<?, ?>`**라
 * 두 템플릿이 모두 후보가 되고 @Primary가 이긴다 — String 템플릿이 @Primary였을 때 외부화가 전부
 * `SerializationException`으로 실패했다(B4-5 로컬 기동). 그래서 Modulith 템플릿을 @Primary로, 재시도 템플릿은 이름으로 고른다.
 * Boot 자동구성 KafkaTemplate은 우리 빈이 있으면 물러난다.
 */
@Configuration
class SearchOutboxKafkaConfig(private val props: KafkaProperties, private val jsonMapper: JsonMapper) {

    @Bean(RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME)
    fun retryTopicKafkaTemplate(): KafkaTemplate<String, String> {
        val cfg = props.buildProducerProperties().toMutableMap()
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(cfg))
    }

    @Bean
    @Primary
    fun modulithKafkaTemplate(): KafkaTemplate<Any, Any> {
        val cfg = props.buildProducerProperties().toMutableMap()
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
        cfg[ProducerConfig.ACKS_CONFIG] = "all"
        cfg[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = true
        cfg[ProducerConfig.MAX_BLOCK_MS_CONFIG] = 5_000
        cfg[ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG] = 5_000
        cfg[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] = 10_000
        return KafkaTemplate(DefaultKafkaProducerFactory<Any, Any>(cfg)).apply {
            setMessageConverter(ByteArrayJacksonJsonMessageConverter(jsonMapper))
        }
    }
}
