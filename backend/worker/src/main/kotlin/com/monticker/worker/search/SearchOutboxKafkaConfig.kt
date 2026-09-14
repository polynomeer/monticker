package com.monticker.worker.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter

/**
 * ADR-042 §2 — worker에는 KafkaTemplate이 두 개 필요하다.
 *
 * - `kafkaTemplate`(기본, String/String): `@RetryableTopic`(틱·알림·발송 컨슈머)이 재시도 토픽·DLT로 레코드를
 *   전달할 때 쓴다. 레코드 값이 String이라 직렬화기도 String이어야 한다 — 이걸 ByteArraySerializer로 바꿨더니
 *   market.ticks 컨슈머의 재시도 전달이 전부 실패하며 133,000줄의 에러가 났다(라이브 검증).
 * - `modulithKafkaTemplate`(Object/Object): Modulith 외부화 전용. `KafkaOperations<Object, Object>`로 주입되고
 *   ByteArrayJsonMessageConverter가 이벤트를 byte[] JSON으로 만들므로 ByteArraySerializer + 짧은 delivery timeout
 *   (브로커 장애 시 스레드를 10초 안에 놓고 Outbox 재전송에 맡긴다 — CH-05).
 *
 * Boot 자동구성 KafkaTemplate은 우리 빈이 있으면 물러난다. 제네릭 타입으로 구분되므로 Modulith는 Object 템플릿을 받는다.
 */
@Configuration
class SearchOutboxKafkaConfig(private val props: KafkaProperties, private val objectMapper: ObjectMapper) {

    @Bean
    @Primary
    fun kafkaTemplate(): KafkaTemplate<String, String> {
        val cfg = props.buildProducerProperties(null).toMutableMap()
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(cfg))
    }

    @Bean
    fun modulithKafkaTemplate(): KafkaTemplate<Any, Any> {
        val cfg = props.buildProducerProperties(null).toMutableMap()
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
        cfg[ProducerConfig.ACKS_CONFIG] = "all"
        cfg[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = true
        cfg[ProducerConfig.MAX_BLOCK_MS_CONFIG] = 5_000
        cfg[ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG] = 5_000
        cfg[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] = 10_000
        return KafkaTemplate(DefaultKafkaProducerFactory<Any, Any>(cfg)).apply {
            setMessageConverter(ByteArrayJsonMessageConverter(objectMapper))
        }
    }
}
