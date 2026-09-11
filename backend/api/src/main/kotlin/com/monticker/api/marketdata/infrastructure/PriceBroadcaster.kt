package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.PriceTick
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * STOMP 시세 발행 — conflation 적용 (ADR-038).
 *
 * 틱을 받는 즉시 발행하지 않고 종목별 버퍼에 **덮어쓴** 뒤 100ms마다 flush한다. 같은 100ms 안에
 * 같은 종목의 틱이 여러 개 오면 마지막 것만 나간다 — 시세는 최신값만 의미가 있으므로 중간 틱을
 * 버려도 사용자에게 보이는 값은 같다. 종목당 최대 10 msg/s로 상한이 걸린다.
 *
 * L-02 기준선(resilience-plan §5.5): 500 연결에서 e2e 지연 max 846ms(전역 토픽 제외 시).
 * 이 꼬리가 conflation의 대상이다.
 *
 * 버퍼는 pod 로컬이고 유실 허용이다(재연결 후 최신값을 받으면 그만). 종료 시 flush하지 않는다.
 */
@Component
class PriceBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    meterRegistry: MeterRegistry,
) {
    // stockId → 최신 틱. 큐가 아니다 — put은 덮어쓴다.
    private val buffer = ConcurrentHashMap<Long, PriceTick>()
    private val ticksIn     = meterRegistry.counter("ws_broadcast_ticks_in_total")
    private val messagesOut = meterRegistry.counter("ws_broadcast_messages_out_total")
    // conflation 비율 = 1 - messages_out / ticks_in. 대시보드에서 계산한다.

    fun broadcast(tick: PriceTick) {
        ticksIn.increment()
        buffer[tick.stockId] = tick
    }

    @Scheduled(fixedRate = 100)
    fun flush() {
        if (buffer.isEmpty()) return
        // 키 스냅샷을 잡고 하나씩 remove — flush 중 들어온 새 틱은 다음 주기에 나간다
        for (stockId in buffer.keys.toList()) {
            val tick = buffer.remove(stockId) ?: continue
            val message = toMessage(tick)
            messagingTemplate.convertAndSend("/topic/stocks/$stockId", message)
            // ADR-039가 폐지한다. 그전까지는 conflation된 스트림이라도 전역 토픽에 흘린다.
            messagingTemplate.convertAndSend("/topic/market", message)
            messagesOut.increment()
        }
    }

    /** 테스트·진단용: 현재 버퍼에 대기 중인 종목 수 */
    fun pending(): Int = buffer.size

    private fun toMessage(tick: PriceTick) = mapOf(
        "type"      to "PRICE_UPDATED",
        "stockId"   to tick.stockId,
        "symbol"    to tick.symbol,
        "price"     to tick.price,
        "volume"    to tick.volume,
        "timestamp" to tick.tradeTime.toString(),
    )
}
