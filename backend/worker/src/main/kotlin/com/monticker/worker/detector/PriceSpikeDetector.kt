package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Detects price spikes by comparing current price change against
 * an EMA of absolute price changes, normalized per symbol.
 *
 * Threshold:
 *   change > 3× EMA of change  → spike detected
 *   direction determines PRICE_SPIKE vs PRICE_DROP
 */
@Component
class PriceSpikeDetector(
    private val redisTemplate: StringRedisTemplate,
    private val writer: StockEventWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emaAlpha = 0.1

    fun detect(tick: GeneratedTick) {
        val prevKey = "detector:price:prev:${tick.symbol}"
        val emaKey  = "detector:price:ema:${tick.symbol}"

        val rawPrev = redisTemplate.opsForValue().get(prevKey)

        if (rawPrev == null) {
            redisTemplate.opsForValue().set(prevKey, tick.price.toPlainString())
            return
        }

        val prev = BigDecimal(rawPrev)
        val change = tick.price.subtract(prev).abs()
        val changePct = if (prev > BigDecimal.ZERO)
            change.divide(prev, 6, java.math.RoundingMode.HALF_UP).toDouble() * 100
        else 0.0

        // Update previous price
        redisTemplate.opsForValue().set(prevKey, tick.price.toPlainString())

        val rawEma = redisTemplate.opsForValue().get(emaKey)
        if (rawEma == null) {
            redisTemplate.opsForValue().set(emaKey, changePct.toString())
            return
        }

        val ema = rawEma.toDouble()
        val newEma = emaAlpha * changePct + (1 - emaAlpha) * ema
        redisTemplate.opsForValue().set(emaKey, newEma.toString())

        val ratio = if (ema > 0.001) changePct / ema else 0.0
        if (ratio < 3.0) return

        val isSpike = tick.price > prev
        val score = when {
            ratio >= 5.0 -> 80
            ratio >= 3.0 -> 55
            else -> 0
        }

        val event = DetectedEvent(
            stockId = tick.stockId,
            eventType = if (isSpike) DetectedEventType.PRICE_SPIKE else DetectedEventType.PRICE_DROP,
            title = if (isSpike)
                "가격 급등 (+${String.format("%.2f", changePct)}%)"
            else
                "가격 급락 (-${String.format("%.2f", changePct)}%)",
            description = "평소 변동 대비 ${String.format("%.1f", ratio)}배 수준의 가격 변화가 감지됐습니다.",
            eventTime = tick.tradeTime,
            importanceScore = score,
            metadataJson = mapOf(
                "symbol" to tick.symbol,
                "prevPrice" to prev.toPlainString(),
                "currentPrice" to tick.price.toPlainString(),
                "changePct" to changePct,
                "ratio" to ratio,
            ),
        )

        writer.write(event)
    }

    /** 이벤트 기록 없이 스파이크 여부만 반환한다 (Spring Integration Router 전용). */
    fun detectWithResult(tick: GeneratedTick): Boolean {
        val prevKey = "detector:price:prev:${tick.symbol}"
        val emaKey  = "detector:price:ema:${tick.symbol}"
        val rawPrev = redisTemplate.opsForValue().get(prevKey) ?: return false
        val rawEma  = redisTemplate.opsForValue().get(emaKey)  ?: return false
        val prev    = java.math.BigDecimal(rawPrev)
        val change  = tick.price.subtract(prev).abs()
        val changePct = if (prev > java.math.BigDecimal.ZERO)
            change.divide(prev, 6, java.math.RoundingMode.HALF_UP).toDouble() * 100 else 0.0
        val ema   = rawEma.toDouble()
        val ratio = if (ema > 0.001) changePct / ema else 0.0
        return ratio >= 3.0
    }
}
