package com.monticker.worker.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter
import org.springframework.modulith.events.Externalized

/**
 * 라이브 검증에서 겪은 사고를 고정한다: 기본 KafkaTemplate을 ByteArraySerializer로 바꾸면 @RetryableTopic의
 * 재시도/DLT 전달(String 레코드)이 전부 실패한다. 기본은 String, Modulith 외부화용은 byte[] + JSON 컨버터여야 한다.
 */
class SearchOutboxKafkaConfigTest {

    private val config = SearchOutboxKafkaConfig(KafkaProperties(), ObjectMapper())

    @Test
    fun `the default template keeps String serializers for RetryableTopic forwarding`() {
        val cfg = config.kafkaTemplate().producerFactory.configurationProperties

        assertThat(cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG]).isEqualTo(StringSerializer::class.java)
    }

    @Test
    fun `the Modulith template serializes byte arrays and converts events to JSON`() {
        val template = config.modulithKafkaTemplate()
        val cfg = template.producerFactory.configurationProperties

        assertThat(cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG]).isEqualTo(ByteArraySerializer::class.java)
        assertThat(cfg[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG]).isEqualTo(10_000)
        assertThat(template.messageConverter).isInstanceOf(ByteArrayJsonMessageConverter::class.java)
    }

    // api의 SearchIndexConsumer가 소비한다 — 토픽·키 규칙이 api와 같아야 한다
    @Test
    fun `the search index event is externalized to search index keyed by index and doc id`() {
        val target = SearchIndexEvent::class.java.getAnnotation(Externalized::class.java).value
        assertThat(target).isEqualTo("search.index::#{#this.index + ':' + #this.docId}")
    }
}
