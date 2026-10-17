package com.monticker.api.common.kafka

import com.monticker.api.support.TestJson
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicBeanNames
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import tools.jackson.databind.json.JsonMapper

/**
 * 컨텍스트 안에서 어느 템플릿이 어디로 가는지 고정한다 — 실제 application.yml의 프로듀서 설정으로.
 * Modulith 외부화는 byte[] 템플릿을, `@RetryableTopic`의 재시도·DLT 전달은 String 템플릿을 받아야 한다.
 */
class KafkaTemplateConfigTest {

    /** Modulith 2.1의 `KafkaEventExternalizerConfiguration.kafkaEventExternalizer(…, KafkaOperations<?, ?>, …)`와 같은 주입점 */
    class StarProjectedConsumer(val operations: KafkaOperations<*, *>)

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties::class)
    class Props

    @Test
    fun `Modulith gets the byte array JSON template and retry topics get the String one by name`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Props::class.java, KafkaTemplateConfig::class.java)
            .withBean(JsonMapper::class.java, { TestJson.boot })
            .withBean(StarProjectedConsumer::class.java)
            .run { ctx ->
                val modulith = ctx.getBean(StarProjectedConsumer::class.java).operations as KafkaTemplate<*, *>
                val cfg = modulith.producerFactory.configurationProperties
                assertThat(cfg[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG]).isEqualTo(ByteArraySerializer::class.java)
                assertThat(cfg[ProducerConfig.ACKS_CONFIG]).isEqualTo("all")   // yml 프로듀서 설정을 그대로 쓴다
                assertThat(modulith.messageConverter).isInstanceOf(ByteArrayJacksonJsonMessageConverter::class.java)

                val retry = ctx.getBean(RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME, KafkaTemplate::class.java)
                assertThat(retry.producerFactory.configurationProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG])
                    .isEqualTo(StringSerializer::class.java)
            }
    }
}
