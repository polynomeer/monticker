package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

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
@Component
class VolumeSurgeDetector(
    private val redisTemplate: StringRedisTemplate,
    private val writer: StockEventWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emaAlpha = 0.1  // smoothing factor

    fun detect(tick: GeneratedTick) {
        val emaKey = "detector:volume:ema:${tick.symbol}"
        val rawEma = redisTemplate.opsForValue().get(emaKey)

        val currentVolume = tick.volume.toDouble()

        if (rawEma == null) {
            // Initialize EMA with first observation
            redisTemplate.opsForValue().set(emaKey, currentVolume.toString())
            return
        }

        val ema = rawEma.toDouble()
        val ratio = if (ema > 0) currentVolume / ema else 1.0

        // Update EMA
        val newEma = emaAlpha * currentVolume + (1 - emaAlpha) * ema
        redisTemplate.opsForValue().set(emaKey, newEma.toString())

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
        val emaKey = "detector:volume:ema:${tick.symbol}"
        val rawEma = redisTemplate.opsForValue().get(emaKey) ?: return false
        val ema    = rawEma.toDouble()
        val ratio  = if (ema > 0) tick.volume.toDouble() / ema else 1.0
        return ratio >= 3.0
    }
}
