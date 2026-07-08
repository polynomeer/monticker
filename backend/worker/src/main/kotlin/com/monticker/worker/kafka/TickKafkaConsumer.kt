package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.detector.EventDetector
import com.monticker.worker.marketdata.CandleAggregator
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.LatencyTracker
import com.monticker.worker.marketdata.RedisTickWriter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Go market-gateway가 발행한 틱을 소비한다 (@KafkaListener 방식).
 *
 * tick.consumer=legacy (기본값) 일 때 활성화.
 * tick.consumer=integration 으로 설정하면 TickPipelineConfig (Spring Integration EIP)가 대신 처리.
 * 두 구현 중 하나만 활성화해 동일 토픽을 중복 소비하지 않는다.
 *
 * 자세한 설계 배경은 docs/technical/kafka-tick-pipeline.md 참고.
 */
@Component
@ConditionalOnProperty(name = ["tick.consumer"], havingValue = "legacy", matchIfMissing = true)
class TickKafkaConsumer(
    private val redisTickWriter: RedisTickWriter,
    private val candleAggregator: CandleAggregator,
    private val eventDetector: EventDetector,
    private val latencyTracker: LatencyTracker,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @KafkaListener(topics = ["market.ticks"], groupId = "monticker-worker")
    fun onTick(record: ConsumerRecord<String, String>) {
        try {
            val tick = objectMapper.readValue(record.value(), GeneratedTick::class.java)
            latencyTracker.recordTickGenerated(tick.stockId, tick.generatedAt)
            redisTickWriter.write(tick)
            candleAggregator.onTick(tick)
            latencyTracker.recordRedisWrite(tick.stockId)
            eventDetector.detect(tick)
            latencyTracker.recordBroadcast(tick.stockId)
        } catch (e: Exception) {
            log.error("Kafka 틱 처리 실패 (key={}): {}", record.key(), e.message)
        }
    }
}
