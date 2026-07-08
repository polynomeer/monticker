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

    /**
     * 이벤트를 탐지하고 감지된 첫 번째 이벤트 유형 문자열을 반환한다.
     * Spring Integration Router가 채널 분기 결정에 사용한다.
     * 복수 이벤트 발생 시 우선순위: PRICE_SPIKE > VOLUME_SURGE > null
     */
    fun detectWithType(tick: GeneratedTick): String? {
        val spikeDetected  = priceSpikeDetector.detectWithResult(tick)
        val surgeDetected  = volumeSurgeDetector.detectWithResult(tick)
        return when {
            spikeDetected -> "PRICE_SPIKE"
            surgeDetected -> "VOLUME_SURGE"
            else          -> null
        }
    }
}
