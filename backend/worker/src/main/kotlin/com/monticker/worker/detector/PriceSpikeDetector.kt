package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.math.BigDecimal

/**
 * Detects price spikes by comparing current price change against
 * an EMA of absolute price changes, normalized per symbol.
 *
 * Threshold:
 *   change > 3× EMA of change  → spike detected
 *   direction determines PRICE_SPIKE vs PRICE_DROP
 */
/**
 * ADR-046 — 감지기 상태(직전 가격, 변동률 EMA)는 프로세스 메모리에 둔다.
 * 이전에는 Redis에 두어 틱마다 GET/SET 4회를 동기로 했다 — L-03 프로파일링에서 컨슈머 스레드 스택 15개 중
 * 13개가 이 Redis 대기였고, 틱당 Redis 왕복 7회(시세 SET 1 + 가격 감지기 4 + 거래량 감지기 2)가 워커 처리
 * 상한 ~600 tick/s의 실제 원인이었다. 같은 종목은 항상 같은 파티션/컨슈머로 오므로(키=stockId) 메모리 상태로
 * 충분하고, 재시작 시 잃는 건 EMA 워밍업(~20틱)뿐이다.
 */
@Component
class PriceSpikeDetector(
    private val writer: StockEventWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emaAlpha = 0.1

    /** symbol → (직전 가격, 변동률 EMA). EMA는 두 번째 틱부터 존재한다. */
    class State(@Volatile var prev: BigDecimal, @Volatile var ema: Double?)
    private val states = ConcurrentHashMap<String, State>()

    /** 테스트·재시작 후 상태 주입용 */
    fun seed(symbol: String, prev: BigDecimal, ema: Double?) { states[symbol] = State(prev, ema) }
    fun stateCount(): Int = states.size

    fun detect(tick: GeneratedTick) {
        val st = states[tick.symbol]
        if (st == null) { states[tick.symbol] = State(tick.price, null); return }
        val prev = st.prev
        val change = tick.price.subtract(prev).abs()
        val changePct = if (prev > BigDecimal.ZERO)
            change.divide(prev, 6, java.math.RoundingMode.HALF_UP).toDouble() * 100
        else 0.0
        st.prev = tick.price
        val ema = st.ema
        if (ema == null) { st.ema = changePct; return }
        val newEma = emaAlpha * changePct + (1 - emaAlpha) * ema
        st.ema = newEma

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
        val st = states[tick.symbol] ?: return false
        val ema = st.ema ?: return false
        val prev = st.prev
        val change  = tick.price.subtract(prev).abs()
        val changePct = if (prev > java.math.BigDecimal.ZERO)
            change.divide(prev, 6, java.math.RoundingMode.HALF_UP).toDouble() * 100 else 0.0
        val ratio = if (ema > 0.001) changePct / ema else 0.0
        return ratio >= 3.0
    }
}
