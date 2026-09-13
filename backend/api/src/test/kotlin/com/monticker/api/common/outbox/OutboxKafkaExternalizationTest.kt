package com.monticker.api.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.events.OrderFilledEvent
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter
import org.springframework.messaging.support.MessageBuilder
import java.math.BigDecimal

/**
 * CH-05에서 발견: Modulith Kafka 외부화는 KafkaJacksonConfiguration의 ByteArrayJsonMessageConverter로
 * 이벤트를 byte[] JSON으로 바꿔 KafkaTemplate.send(Message)에 넘긴다. application.yml의 producer
 * value-serializer가 StringSerializer면 "Can't convert value of class [B"로 모든 외부화가 실패한다 —
 * trading.order-filled가 Kafka에 한 번도 도달하지 못했다. 이 테스트는 yml에 적힌 직렬화기 클래스를 실제로
 * 로드해 Modulith가 보내는 형태의 메시지를 통과시킨다.
 */
class OutboxKafkaExternalizationTest {

    private fun configuredValueSerializer(): Serializer<Any> {
        val props = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()!!
        val cls = props.getProperty("spring.kafka.producer.value-serializer")
        @Suppress("UNCHECKED_CAST")
        return Class.forName(cls).getDeclaredConstructor().newInstance() as Serializer<Any>
    }

    /** Boot가 자동구성하는 KafkaTemplate과 같은 조합: yml 직렬화기 + Modulith가 등록하는 ByteArrayJsonMessageConverter */
    private fun templateWith(valueSerializer: Serializer<Any>): Pair<KafkaTemplate<Any, Any>, MockProducer<Any, Any>> {
        @Suppress("UNCHECKED_CAST")
        val producer = MockProducer(true, StringSerializer() as Serializer<Any>, valueSerializer)
        val factory = object : ProducerFactory<Any, Any> {
            override fun createProducer(): Producer<Any, Any> = producer
        }
        val template = KafkaTemplate(factory).apply { setMessageConverter(ByteArrayJsonMessageConverter(ObjectMapper().findAndRegisterModules())) }
        return template to producer
    }

    private val event = OrderFilledEvent(
        orderId = 1L, userId = 42L, stockId = 7L, fillId = 3L, side = "BUY",
        quantity = 2, fillPrice = BigDecimal("65000"), amount = BigDecimal("130000"),
    )

    private fun modulithStyleMessage() = MessageBuilder.withPayload(event as Any)
        .setHeader(KafkaHeaders.TOPIC, "trading.order-filled")
        .setHeader(KafkaHeaders.KEY, event.userId.toString())
        .build()

    @Test
    fun `the configured value serializer accepts what the Modulith externalizer sends`() {
        val (template, producer) = templateWith(configuredValueSerializer())

        template.send(modulithStyleMessage()).get()

        val record = producer.history().single()
        assertThat(record.topic()).isEqualTo("trading.order-filled")
        assertThat(record.key()).isEqualTo("42")
        assertThat(String(record.value() as ByteArray)).contains("\"userId\":42").contains("\"fillPrice\":65000")
    }

    @Test
    fun `a StringSerializer — the previous configuration — rejects the byte array payload`() {
        @Suppress("UNCHECKED_CAST")
        val stringSerializer = StringSerializer() as Serializer<Any>
        val (template, _) = templateWith(stringSerializer)

        // 실제 KafkaProducer는 이 ClassCastException을 "Can't convert value of class [B ... StringSerializer"로 감싼다
        assertThatThrownBy { template.send(modulithStyleMessage()).get() }
            .isInstanceOf(ClassCastException::class.java)
            .hasMessageContaining("[B cannot be cast to class java.lang.String")
    }
}
