package com.monticker.api.common.kafka

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder

/** ADR-040 — 토픽 선언이 프로퍼티와 Spring Kafka의 재시도 토픽 명명 규칙을 따르는지. */
class KafkaTopicConfigTest {

    private val props = KafkaTopicProperties(
        replicationFactor = 1,
        partitions = KafkaTopicProperties.Partitions(marketTicks = 64, tickProcessed = 32, marketEvents = 16, trading = 16, retry = 4),
    )
    private val config = KafkaTopicConfig(props)

    @Test
    fun `market ticks 파티션은 프로퍼티를 따르고 summary는 항상 1개다`() {
        assertThat(config.marketTicksTopic().numPartitions()).isEqualTo(64)
        assertThat(config.marketSummaryTopic().numPartitions()).isEqualTo(1)
        assertThat(config.tickProcessedTopic().numPartitions()).isEqualTo(32)
    }

    @Test
    fun `단일 브로커에서는 trading 토픽에 min insync replicas 를 걸지 않는다 - 걸면 쓰기가 막힌다`() {
        assertThat(config.orderFilledTopic().configs()).doesNotContainKey("min.insync.replicas")
        val replicated = KafkaTopicConfig(props.copy(replicationFactor = 3))
        assertThat(replicated.orderFilledTopic().configs()["min.insync.replicas"]).isEqualTo("2")
    }

    @Test
    fun `재시도 토픽 이름이 Spring Kafka RetryableTopic 의 실제 명명 규칙과 일치한다`() {
        // 우리 규칙을 문자열로 재현한 게 아니라, 라이브러리가 attempts=4 에서 실제로 만드는 suffix와 대조한다
        val spring = RetryTopicConfigurationBuilder.newInstance()
            .maxAttempts(4).suffixTopicsWithIndexValues().dltSuffix("-dlt")
            .create(mockk<KafkaOperations<Any, Any>>())
        val expected = spring.destinationTopicProperties.map { "trading.order-filled" + it.suffix() }
            .filter { it != "trading.order-filled" }          // 원본 토픽 자신은 제외
        assertThat(KafkaTopicConfig.RetryFamilies.names("trading.order-filled", 4))
            .containsExactlyElementsOf(expected)
    }

    @Test
    fun `RetryableTopic 컨슈머의 재시도 패밀리가 전부 선언된다`() {
        val declared = config.declaredRetryTopics().map { it.name() }
        assertThat(declared).contains(
            "market.ticks-retry-0", "market.ticks-retry-1", "market.ticks-dlt",
            "market.tick-processed-retry-0", "market.tick-processed-retry-1", "market.tick-processed-dlt",
            "notify.commands-retry-0", "notify.commands-retry-1", "notify.commands-dlt",
            "search.index-dlt",   // ADR-042 — 배치 컨슈머는 블로킹 재시도라 retry 토픽 없이 DLT만
        )
        assertThat(declared).hasSize(10)   // ADR-049: order-filled 패밀리는 소비자(quant-engine)와 함께 제거
    }
}
