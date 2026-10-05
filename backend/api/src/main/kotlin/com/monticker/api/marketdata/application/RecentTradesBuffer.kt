package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 체결 틱 한 건 — direction은 직전 틱 대비(UP·DOWN·FLAT). source는 시세 출처(KIS·TOSS·MOCK·UNKNOWN). */
data class RecentTrade(
    val price: BigDecimal,
    val volume: Long,
    val tradeTime: Instant,
    val direction: String,
    val source: String,
)

/**
 * 종목별 최근 체결 틱 링 버퍼 — 종목 화면 호가 패널 "체결" 탭과 매칭 화면 체결 테이프.
 *
 * 틱은 DB에 남지 않는다(`price_ticks`는 쓰는 곳이 없고, 분봉만 저장된다). 대신 api pod마다
 * MarketTickBroadcastConsumer가 market.ticks의 **전 파티션**을 받으므로(ADR-038) 각 pod의 버퍼는 같은 틱을 본다
 * — pod가 뜬 뒤부터의 틱이라는 차이만 있다. 재시작하면 비어 있다가 다시 찬다. 영속 이력이 아니라 "최근 테이프"다.
 *
 * 메모리 상한: 종목당 [capacity]건 × 종목 [maxStocks]개. 동기 @EventListener지만 일은 O(1) 추가뿐이다.
 */
@Component
class RecentTradesBuffer(
    @Value("\${app.marketdata.recent-trades.capacity:200}") private val capacity: Int = 200,
    @Value("\${app.marketdata.recent-trades.max-stocks:5000}") private val maxStocks: Int = 5_000,
) {
    private val buffers = ConcurrentHashMap<Long, ArrayDeque<RecentTrade>>()

    @EventListener
    fun onTick(event: MarketTickReceivedEvent) {
        val tick = event.tick
        if (!buffers.containsKey(tick.stockId) && buffers.size >= maxStocks) return
        val buf = buffers.computeIfAbsent(tick.stockId) { ArrayDeque(capacity) }
        synchronized(buf) {
            val prev = buf.lastOrNull()?.price
            val direction = when {
                prev == null -> "FLAT"
                tick.price > prev -> "UP"
                tick.price < prev -> "DOWN"
                else -> "FLAT"
            }
            if (buf.size >= capacity) buf.removeFirst()
            buf.addLast(RecentTrade(tick.price, tick.volume, tick.tradeTime, direction, event.provenance.source.name))
        }
    }

    /** 최신순 최대 [limit]건. */
    fun recent(stockId: Long, limit: Int): List<RecentTrade> {
        val buf = buffers[stockId] ?: return emptyList()
        val n = limit.coerceIn(1, capacity)
        return synchronized(buf) { buf.toList().takeLast(n).asReversed() }
    }
}
