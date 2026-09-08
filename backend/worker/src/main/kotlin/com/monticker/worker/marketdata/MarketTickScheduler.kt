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
 * role=all    : 단일 프로세스 모드. ingestion.source가 정확히 kafka이면 Go market-gateway가
 *               market.ticks를 대신 발행하므로 이 스케줄러를 비활성화한다.
 * ingestion.source=kis, toss, "kis,toss" 등 kafka 이외의 값에서는 이 스케줄러가 계속
 * 돈다 — 실시간 프로바이더가 커버하지 않는 나머지 종목은 여전히 Mock이 채워야 하기
 * 때문이다. 실제 종목 단위 제외는 MockPriceGenerator가 KisCoverageProvider/
 * TossCoverageProvider를 참조해 처리한다(ADR-030, ADR-031). "kafka 외 전부 허용" 방식이라
 * 새 프로바이더가 추가돼도 이 조건식을 매번 고칠 필요가 없다.
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
    "'\${worker.role:all}'.matches('market|all') && !'\${ingestion.source:internal}'.equals('kafka')"
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
