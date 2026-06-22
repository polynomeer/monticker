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
    private val candleAggregator: CandleAggregator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun collect() {
        try {
            val ticks = generator.generate()
            ticks.forEach { tick ->
                writer.write(tick)
                eventDetector.detect(tick)
                candleAggregator.onTick(tick)
            }
            log.debug("Published {} ticks", ticks.size)
        } catch (e: Exception) {
            log.error("Tick collection failed — skipping cycle", e)
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun flushCandles() {
        try {
            candleAggregator.flushAll()
            log.debug("Flushed current-minute candles")
        } catch (e: Exception) {
            log.error("Candle flush failed", e)
        }
    }
}
