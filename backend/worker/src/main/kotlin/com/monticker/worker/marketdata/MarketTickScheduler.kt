package com.monticker.worker.marketdata

import com.monticker.worker.kafka.TickKafkaProducer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * worker.role=market 전용 스케줄러.
 * MockPriceGenerator 틱을 Kafka market.ticks 토픽으로 발행하고 종료한다.
 * CandleAggregator/EventDetector/AlertEvaluator 는 각각 event/alert 워커가 담당한다.
 */
@Component
@EnableScheduling
@ConditionalOnExpression("'\${worker.role:all}' == 'market'")
class MarketTickScheduler(
    private val generator: MockPriceGenerator,
    private val producer: TickKafkaProducer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun collect() {
        try {
            generator.generate().forEach { tick -> producer.publish(tick) }
        } catch (e: Exception) {
            log.error("시세 수집 실패 — 이번 사이클 건너뜀", e)
        }
    }
}
