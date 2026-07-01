package com.monticker.worker.marketdata

import com.monticker.worker.detector.EventDetector
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
class MarketDataCollector(
    private val generator: MockPriceGenerator,
    private val writer: RedisTickWriter,
    private val candleAggregator: CandleAggregator,
    private val eventDetector: EventDetector,
    private val latencyTracker: LatencyTracker,
    @Value("\${ingestion.source:internal}") private val ingestionSource: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun collect() {
        // ingestion.source=kafka일 때는 Go market-gateway → TickKafkaConsumer 경로로
        // 틱이 들어오므로, 내부 생성기를 동시에 돌리면 데이터가 중복된다. (ADR-005)
        if (ingestionSource == "kafka") return
        try {
            val ticks = generator.generate()
            ticks.forEach { tick ->
                latencyTracker.recordTickGenerated(tick.stockId, tick.generatedAt)
                writer.write(tick)
                candleAggregator.onTick(tick)
                latencyTracker.recordRedisWrite(tick.stockId)
                eventDetector.detect(tick)
                latencyTracker.recordBroadcast(tick.stockId)
            }
            log.debug("Published {} ticks", ticks.size)
        } catch (e: Exception) {
            log.error("Tick collection failed — skipping cycle", e)
        }
    }
}
