package com.monticker.worker.marketdata

import com.monticker.worker.kafka.TickKafkaProducer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * MockPriceGenerator 틱을 Kafka market.ticks 토픽으로 발행하는 스케줄러.
 *
 * role=market : MSA 독립 컨테이너 모드.
 * role=all    : 단일 프로세스 모드. ingestion.source=kafka 이면 Go market-gateway가
 *               market.ticks를 대신 발행하므로 이 스케줄러를 비활성화한다.
 *
 * [Stage 4] 내부 경로와 Go gateway 경로 모두 Kafka를 거친다.
 * CandleAggregator/EventDetector/AlertEvaluator는 TickKafkaConsumer가 담당.
 */
@Component
@EnableScheduling
@ConditionalOnExpression(
    "'\${worker.role:all}'.matches('market|all') && '\${ingestion.source:internal}' != 'kafka'"
)
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
