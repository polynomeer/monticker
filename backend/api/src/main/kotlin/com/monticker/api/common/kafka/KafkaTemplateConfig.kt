package com.monticker.api.common.kafka

import org.apache.kafka.clients.producer.ProducerConfig
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
 * api의 KafkaTemplate 두 개 — worker의 SearchOutboxKafkaConfig와 같은 구조다.
 *
 * - `modulithKafkaTemplate`(**@Primary**): Modulith 외부화(@Externalized OrderFilledEvent 등). application.yml의 프로듀서
 *   설정(ByteArraySerializer, acks=all, 멱등성, CH-05 타임아웃)에 Boot 매퍼(Jackson 3, ADR-104)로 byte[] JSON을 만드는
 *   컨버터를 얹는다 — Boot 자동 구성 템플릿이 하던 일과 같다. Modulith 2는 `KafkaOperations<?, ?>`로 주입받아 @Primary를 잡는다.
 * - `defaultRetryTopicKafkaTemplate`(String/String): `@RetryableTopic`(WatchRuleConsumer)이 재시도·DLT 토픽으로 **컨슈머
 *   레코드(String)를 그대로** 넘길 때 쓴다. Spring Kafka가 이 이름으로 먼저 찾는다.
 *
 * 예전엔 템플릿이 Boot 자동 구성 하나(ByteArraySerializer)뿐이라 재시도 전달이 `SerializationException`으로 실패했고, 오류
 * 처리기가 같은 레코드를 계속 다시 시도해 **잘못된 메시지 하나가 market.event-detected 파티션을 멈췄다**(B4-5 로컬 검증에서
 * 발견, Boot 3.5에서도 같았다). 템플릿 빈을 하나만 추가하면 Boot 자동 구성 템플릿이 물러나 Modulith가 String 템플릿을 잡으므로
 * 둘 다 명시한다.
 */
@Configuration
class KafkaTemplateConfig(private val props: KafkaProperties, private val jsonMapper: JsonMapper) {

    @Bean
    @Primary
    fun modulithKafkaTemplate(): KafkaTemplate<Any, Any> =
        KafkaTemplate(DefaultKafkaProducerFactory<Any, Any>(props.buildProducerProperties())).apply {
            setMessageConverter(ByteArrayJacksonJsonMessageConverter(jsonMapper))
        }

    @Bean(RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME)
    fun retryTopicKafkaTemplate(): KafkaTemplate<String, String> {
        val cfg = props.buildProducerProperties().toMutableMap()
        cfg[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(cfg))
    }
}
