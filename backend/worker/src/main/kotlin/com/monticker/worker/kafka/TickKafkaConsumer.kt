package com.monticker.worker.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.detector.EventDetector
import com.monticker.worker.marketdata.CandleAggregator
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.LatencyTracker
import com.monticker.worker.marketdata.RedisTickWriter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Go market-gateway(또는 MarketTickScheduler)가 발행한 틱을 소비한다.
 *
 * role=all  : tick.consumer=legacy(기본값)일 때 활성화. in-process 처리 후 Spring Event 발행.
 * role=event: 항상 활성화. 처리 후 market.tick-processed 를 Kafka로 발행해 alert 워커에 전파.
 *
 * tick.consumer=integration 이면 TickPipelineConfig(Spring Integration EIP)가 대신 처리하므로
 * 이 Bean이 비활성화되어야 한다. role=event 에서는 integration 모드를 사용하지 않는다.
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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    // role=event 에서만 Bean이 존재한다. role=all 에서는 null — Spring Event가 대신 전파.
    @Autowired(required = false)
    private var tickProcessedProducer: TickProcessedKafkaProducer? = null

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
            tickProcessedProducer?.publish(tick.stockId, tick.price)
        } catch (e: Exception) {
            log.error("Kafka 틱 처리 실패 (key={}): {}", record.key(), e.message)
        }
    }
}
