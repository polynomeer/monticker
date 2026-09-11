package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects volume surges by comparing current tick volume against
 * an exponential moving average (EMA) maintained in Redis.
 *
 * Uses EMA as a lightweight substitute for 20-day same-time-of-day average
 * during the mock phase (no historical data available).
 *
 * Threshold:
 *   3× EMA  → meaningful signal (importanceScore 60)
 *   5× EMA  → strong signal    (importanceScore 85)
 */
/** ADR-046 — 거래량 EMA 상태를 메모리에 둔다 (PriceSpikeDetector 주석 참고: 틱당 Redis 왕복이 처리 상한이었다). */
@Component
class VolumeSurgeDetector(
    private val writer: StockEventWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emaAlpha = 0.1  // smoothing factor

    private val emas = ConcurrentHashMap<String, Double>()   // symbol → 거래량 EMA

    fun seed(symbol: String, ema: Double) { emas[symbol] = ema }

    fun detect(tick: GeneratedTick) {
        val currentVolume = tick.volume.toDouble()
        val ema = emas[tick.symbol]
        if (ema == null) { emas[tick.symbol] = currentVolume; return }   // 첫 관측으로 초기화
        val ratio = if (ema > 0) currentVolume / ema else 1.0
        emas[tick.symbol] = emaAlpha * currentVolume + (1 - emaAlpha) * ema
        if (ratio < 3.0) return

        val score = when {
            ratio >= 5.0 -> 85
            ratio >= 3.0 -> 60
            else -> 0
        }

        val event = DetectedEvent(
            stockId = tick.stockId,
            eventType = DetectedEventType.VOLUME_SURGE,
            title = "거래량 급증 (${String.format("%.1f", ratio)}배)",
            description = "평균 거래량 대비 ${String.format("%.1f", ratio)}배 수준의 거래량이 감지됐습니다.",
            eventTime = tick.tradeTime,
            importanceScore = score,
            metadataJson = mapOf(
                "symbol" to tick.symbol,
                "volume" to tick.volume,
                "ema" to ema,
                "ratio" to ratio,
            ),
        )

        writer.write(event)
    }

    /** 이벤트 기록 없이 서지 여부만 반환한다 (Spring Integration Router 전용). */
    fun detectWithResult(tick: GeneratedTick): Boolean {
        val ema = emas[tick.symbol] ?: return false
        val ratio = if (ema > 0) tick.volume.toDouble() / ema else 1.0
        return ratio >= 3.0
    }
}
