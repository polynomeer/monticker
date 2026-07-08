package com.monticker.worker.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.TickProcessedEvent
import com.monticker.worker.detector.EventDetector
import com.monticker.worker.marketdata.CandleAggregator
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.LatencyTracker
import com.monticker.worker.marketdata.RedisTickWriter
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.channel.PublishSubscribeChannel
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.dsl.integrationFlow
import org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.messaging.MessageChannel

/**
 * 시세 틱 파이프라인 — Spring Integration EIP 패턴 구현.
 *
 * 파이프라인 구조:
 *
 *   Kafka[market.ticks]
 *     └─ KafkaMessageDrivenChannelAdapter
 *          └─ rawTickChannel (DirectChannel)         ← String JSON 메시지
 *               └─ Transformer (JSON → GeneratedTick)
 *                    └─ tickChannel (PublishSubscribeChannel)  ← fan-out
 *                         ├─ [1] latency record + Redis write endpoint
 *                         ├─ [2] Candle aggregator endpoint
 *                         └─ [3] Event detector endpoint
 *                                  └─ detectedEventChannel (DirectChannel)
 *                                       └─ Router
 *                                            ├─ PRICE_SPIKE  → spikeChannel
 *                                            ├─ VOLUME_SURGE → surgeChannel
 *                                            └─ (default)    → normalChannel (nullChannel)
 *
 * @ConditionalOnProperty(ingestion.source=kafka): Kafka 경로에서만 활성화.
 *   내부 MockPriceGenerator(ingestion.source=internal) 경로와 중복 방지.
 *
 * TickKafkaConsumer(@KafkaListener 방식)는 이 파이프라인으로 완전히 대체된다.
 */
@Configuration
@EnableIntegration
@ConditionalOnProperty(name = ["tick.consumer"], havingValue = "integration")
class TickPipelineConfig(
    private val redisTickWriter: RedisTickWriter,
    private val candleAggregator: CandleAggregator,
    private val eventDetector: EventDetector,
    private val latencyTracker: LatencyTracker,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${spring.kafka.bootstrap-servers:localhost:9092}") private val bootstrapServers: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    // ── 채널 정의 ──────────────────────────────────────────────────────────────

    /** Kafka에서 수신한 raw JSON String 메시지 채널 */
    @Bean
    fun rawTickChannel(): MessageChannel = DirectChannel()

    /**
     * fan-out 채널 — PublishSubscribeChannel은 구독자 모두에게 동시 전달.
     * taskExecutor를 지정하지 않으면 발행 스레드에서 동기 순차 전달한다.
     */
    @Bean
    fun tickChannel(): MessageChannel = PublishSubscribeChannel()

    /** 이벤트 탐지 결과(null 가능) 전달 채널 */
    @Bean
    fun detectedEventChannel(): MessageChannel = DirectChannel()

    // ── Kafka Inbound Adapter ──────────────────────────────────────────────────

    @Bean
    fun kafkaListenerContainer(): KafkaMessageListenerContainer<String, String> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG  to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG           to "monticker-worker-integration",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG   to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG  to "latest",
        )
        val factory: ConsumerFactory<String, String> = DefaultKafkaConsumerFactory(props)
        return KafkaMessageListenerContainer(factory, ContainerProperties("market.ticks"))
    }

    @Bean
    fun kafkaInboundAdapter(): KafkaMessageDrivenChannelAdapter<String, String> =
        KafkaMessageDrivenChannelAdapter(kafkaListenerContainer()).apply {
            setOutputChannel(rawTickChannel())
        }

    // ── Integration Flow ───────────────────────────────────────────────────────

    /**
     * rawTickChannel → JSON Transformer → tickChannel (PublishSubscribeChannel fan-out)
     */
    @Bean
    fun tickDeserializeFlow(): IntegrationFlow = integrationFlow(rawTickChannel()) {
        // JSON → GeneratedTick. 역직렬화 실패 시 MessagingException이 발생하여
        // Spring Integration 에러 채널(errorChannel)로 라우팅된다.
        transform<String> { json: String ->
            try {
                objectMapper.readValue(json, GeneratedTick::class.java)
            } catch (e: Exception) {
                log.error("[Pipeline] JSON 역직렬화 실패: {}", e.message)
                throw e
            }
        }
        channel(tickChannel())
    }

    /**
     * Subscriber 1: 지연 시간 기록 + Redis 틱 저장
     */
    @Bean
    fun redisWriteFlow(): IntegrationFlow = integrationFlow(tickChannel()) {
        handle<GeneratedTick> { tick, _ ->
            latencyTracker.recordTickGenerated(tick.stockId, tick.generatedAt)
            redisTickWriter.write(tick)
            latencyTracker.recordRedisWrite(tick.stockId)
        }
    }

    /**
     * Subscriber 2: 1분봉 집계
     */
    @Bean
    fun candleAggregateFlow(): IntegrationFlow = integrationFlow(tickChannel()) {
        handle<GeneratedTick> { tick, _ ->
            candleAggregator.onTick(tick)
        }
    }

    /**
     * Subscriber 3: 이벤트 탐지 → detectedEventChannel
     * Router가 이벤트 유형에 따라 분기한다.
     */
    @Bean
    fun eventDetectFlow(): IntegrationFlow = integrationFlow(tickChannel()) {
        transform<GeneratedTick> { tick ->
            latencyTracker.recordBroadcast(tick.stockId)
            eventDetector.detect(tick)  // 이벤트 감지 + DB 기록
            TickWithEvent(tick, eventDetector.detectWithType(tick))  // 유형 추출 (Router용)
        }
        channel(detectedEventChannel())
    }

    /**
     * Router: 이벤트 유형별 분기.
     *   PRICE_SPIKE  → spikeChannel
     *   VOLUME_SURGE → surgeChannel
     *   null / NONE  → loggingChannel (로그만 남기고 버림)
     */
    @Bean
    fun eventRouterFlow(): IntegrationFlow = integrationFlow(detectedEventChannel()) {
        route<TickWithEvent, String>(
            { msg -> msg.eventType ?: "NONE" },
            {
                channelMapping("PRICE_SPIKE",  "spikeChannel")
                channelMapping("VOLUME_SURGE", "surgeChannel")
                defaultOutputChannel("loggingChannel")
            }
        )
    }

    @Bean fun spikeChannel():   MessageChannel = DirectChannel()
    @Bean fun surgeChannel():   MessageChannel = DirectChannel()
    @Bean fun loggingChannel(): MessageChannel = DirectChannel()

    @Bean
    fun spikeFlow(): IntegrationFlow = integrationFlow(spikeChannel()) {
        handle<TickWithEvent> { msg, _ ->
            log.info("[Pipeline/SPIKE] stockId={} price={}", msg.tick.stockId, msg.tick.price)
        }
    }

    @Bean
    fun surgeFlow(): IntegrationFlow = integrationFlow(surgeChannel()) {
        handle<TickWithEvent> { msg, _ ->
            log.info("[Pipeline/SURGE] stockId={} volume={}", msg.tick.stockId, msg.tick.volume)
        }
    }

    @Bean
    fun loggingFlow(): IntegrationFlow = integrationFlow(loggingChannel()) {
        handle<TickWithEvent> { msg, _ ->
            log.trace("[Pipeline/NORMAL] stockId={}", msg.tick.stockId)
        }
    }

    /**
     * Subscriber 4: 알림 규칙 실시간 평가 (EDA).
     * tickChannel 팬아웃으로 수신한 틱의 stockId + price를 Spring Application Event로 발행한다.
     * AlertEvaluator가 @EventListener + @Async로 받아 처리하므로 파이프라인을 블로킹하지 않는다.
     */
    @Bean
    fun alertEvaluateFlow(): IntegrationFlow = integrationFlow(tickChannel()) {
        handle<GeneratedTick> { tick, _ ->
            eventPublisher.publishEvent(TickProcessedEvent(tick.stockId, tick.price))
        }
    }
}

/** 이벤트 탐지 결과를 틱과 함께 라우터로 전달하기 위한 운반체 */
data class TickWithEvent(
    val tick: GeneratedTick,
    val eventType: String?,
)
