package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import org.springframework.stereotype.Component

@Component
class EventDetector(
    private val volumeSurgeDetector: VolumeSurgeDetector,
    private val priceSpikeDetector: PriceSpikeDetector,
) {
    fun detect(tick: GeneratedTick) {
        volumeSurgeDetector.detect(tick)
        priceSpikeDetector.detect(tick)
    }
}
