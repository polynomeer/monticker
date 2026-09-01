package com.monticker.worker.marketdata

import com.monticker.worker.kafka.TickKafkaProducer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
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
 *
 * @EnableScheduling은 여기가 아니라 WorkerApplication에 둔다 — 이 빈은 role/ingestion
 * 조건에 따라 등록 자체가 스킵될 수 있어서, 스케줄링 활성화를 이 빈에 묶으면 그 조건이
 * 거짓인 배포에서 다른 모든 @Scheduled 컬렉터까지 함께 죽는다.
 */
@Component
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
