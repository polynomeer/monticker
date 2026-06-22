package com.monticker.worker.marketdata

import com.monticker.worker.detector.EventDetector
import com.monticker.worker.kis.KisPriceProvider
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
    private val kisPriceProvider: KisPriceProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun collect() {
        try {
            val kisTicks = kisPriceProvider.fetchTicks()
            val ticks = if (kisTicks.isNotEmpty()) kisTicks else generator.generate()
            ticks.forEach { tick ->
                writer.write(tick)
                eventDetector.detect(tick)
            }
            log.debug("Published {} ticks", ticks.size)
        } catch (e: Exception) {
            log.error("Tick collection failed — skipping cycle", e)
        }
    }
}
