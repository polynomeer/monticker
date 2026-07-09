package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.TickProcessedEvent
import com.monticker.worker.detector.EventDetector
import com.monticker.worker.marketdata.CandleAggregator
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.LatencyTracker
import com.monticker.worker.marketdata.RedisTickWriter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component

/**
 * market.ticks 토픽 컨슈머 — 시세 처리 파이프라인의 공통 진입점.
 *
 * [Stage 4] 모든 틱 경로(내부 Mock/Go gateway)가 Kafka를 거친다.
 * MarketDataCollector(인라인 처리)는 제거되었고, MarketTickScheduler가
 * MockPriceGenerator 틱을 market.ticks로 발행한다.
 *
 * role=all  : tick.consumer=legacy(기본값)일 때 활성화.
 *             처리 후 Spring ApplicationEvent(TickProcessedEvent)를 발행해
 *             AlertEvaluator(@EventListener)가 in-process로 수신한다.
 * role=event: 항상 활성화.
 *             처리 후 market.tick-processed 토픽으로 Kafka 발행해
 *             alert 워커에 전파한다.
 *
 * tick.consumer=integration 이면 TickPipelineConfig(Spring Integration EIP)가
 * 대신 처리하므로 이 Bean이 비활성화되어야 한다. role=event 에서는 integration 모드 미사용.
 */
@Component
@ConditionalOnExpression(
    "'\${worker.role:all}' == 'event' || '\${tick.consumer:legacy}' == 'legacy'"
)
class TickKafkaConsumer(
    private val redisTickWriter: RedisTickWriter,
    private val candleAggregator: CandleAggregator,
    private val eventDetector: EventDetector,
    private val latencyTracker: LatencyTracker,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    // role=event 에서만 Bean이 존재한다.
    // role=all 에서는 null — Spring Event가 AlertEvaluator에 전파한다.
    @Autowired(required = false)
    private var tickProcessedProducer: TickProcessedKafkaProducer? = null

    @RetryableTopic(
        attempts = "3",
        backoff = Backoff(delay = 2_000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "false",
    )
    @KafkaListener(topics = ["market.ticks"], groupId = "monticker-worker")
    fun onTick(record: ConsumerRecord<String, String>) {
        val tick = objectMapper.readValue(record.value(), GeneratedTick::class.java)
        latencyTracker.recordTickGenerated(tick.stockId, tick.generatedAt)
        redisTickWriter.write(tick)
        candleAggregator.onTick(tick)
        latencyTracker.recordRedisWrite(tick.stockId)
        eventDetector.detect(tick)
        latencyTracker.recordBroadcast(tick.stockId)
        // role=event → Kafka market.tick-processed 발행 (alert 워커가 소비)
        // role=all   → Spring Event 발행 (AlertEvaluator @EventListener가 in-process 수신)
        tickProcessedProducer?.publish(tick.stockId, tick.price)
            ?: eventPublisher.publishEvent(TickProcessedEvent(tick.stockId, tick.price))
    }

    @DltHandler
    fun onTickDlt(record: ConsumerRecord<String, String>) {
        log.error(
            "[DLT] market.ticks 최종 실패 — 수동 검토 필요. " +
            "topic={} partition={} offset={} key={}",
            record.topic(), record.partition(), record.offset(), record.key(),
        )
    }
}
