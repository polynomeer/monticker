package com.monticker.worker.marketdata

import com.monticker.worker.detector.EventDetector
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
class MarketDataCollector(
    private val generator: MockPriceGenerator,
    private val writer: RedisTickWriter,
    private val eventDetector: EventDetector,
    private val latencyTracker: LatencyTracker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun collect() {
        try {
            val ticks = generator.generate()
            ticks.forEach { tick ->
                latencyTracker.recordTickGenerated(tick.stockId, tick.generatedAt)
                writer.write(tick)
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
