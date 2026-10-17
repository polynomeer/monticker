package com.monticker.worker.search

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicBeanNames
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import org.springframework.modulith.events.Externalized
import tools.jackson.databind.json.JsonMapper

/**
 * 라이브 검증에서 겪은 사고를 고정한다: 기본 KafkaTemplate을 ByteArraySerializer로 바꾸면 @RetryableTopic의
 * 재시도/DLT 전달(String 레코드)이 전부 실패한다. 기본은 String, Modulith 외부화용은 byte[] + JSON 컨버터여야 한다.
 */
class SearchOutboxKafkaConfigTest {

    private val config = SearchOutboxKafkaConfig(KafkaProperties(), JsonMapper())

    @Test
    fun `the default template keeps String serializers for RetryableTopic forwarding`() {
        val cfg = config.retryTopicKafkaTemplate().producerFactory.configurationProperties

        assertThat(cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG]).isEqualTo(StringSerializer::class.java)
    }

    @Test
    fun `the Modulith template serializes byte arrays and converts events to JSON`() {
        val template = config.modulithKafkaTemplate()
        val cfg = template.producerFactory.configurationProperties

        assertThat(cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG]).isEqualTo(ByteArraySerializer::class.java)
        assertThat(cfg[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG]).isEqualTo(10_000)
        assertThat(template.messageConverter).isInstanceOf(ByteArrayJacksonJsonMessageConverter::class.java)
    }

    /** Modulith 2.1의 `KafkaEventExternalizerConfiguration.kafkaEventExternalizer(…, KafkaOperations<?, ?>, …)`와 같은 주입점 */
    class StarProjectedConsumer(val operations: KafkaOperations<*, *>)

    @Test
    fun `in a context Modulith gets the byte array template and retry topics get the String one by name`() {
        ApplicationContextRunner()
            .withBean(KafkaProperties::class.java)
            .withBean(JsonMapper::class.java, { JsonMapper() })
            .withUserConfiguration(SearchOutboxKafkaConfig::class.java)
            .withBean(StarProjectedConsumer::class.java)
            .run { ctx ->
                val modulith = ctx.getBean(StarProjectedConsumer::class.java).operations as KafkaTemplate<*, *>
                assertThat(modulith.producerFactory.configurationProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG])
                    .isEqualTo(ByteArraySerializer::class.java)
                // Spring Kafka의 RetryableTopicAnnotationProcessor가 이 이름으로 먼저 찾는다
                val retry = ctx.getBean(RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME, KafkaTemplate::class.java)
                assertThat(retry.producerFactory.configurationProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG])
                    .isEqualTo(StringSerializer::class.java)
            }
    }

    // api의 SearchIndexConsumer가 소비한다 — 토픽·키 규칙이 api와 같아야 한다
    @Test
    fun `the search index event is externalized to search index keyed by index and doc id`() {
        val target = SearchIndexEvent::class.java.getAnnotation(Externalized::class.java).value
        assertThat(target).isEqualTo("search.index::#{#this.index + ':' + #this.docId}")
    }
}
