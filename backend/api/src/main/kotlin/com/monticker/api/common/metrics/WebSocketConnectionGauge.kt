package com.monticker.api.common.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * pod당 활성 STOMP 세션 수 (resilience-plan §C3s / P1-2, ADR-038).
 *
 * fan-out 비용은 연결 수에 비례하고, SimpleBroker가 pod 로컬이라 연결이 한 pod에 몰리면
 * 그 pod만 GC 압박을 받는다. 지금은 이걸 볼 방법이 없었다. 알람: WsConnectionsSkewed.
 * ADR-038의 티어 분리 트리거("pod당 5,000 연결")도 이 게이지로 판단한다.
 */
@Component
class WebSocketConnectionGauge(registry: MeterRegistry) {

    private val active = AtomicInteger(0)

    init {
        Gauge.builder("ws_active_connections", active) { it.get().toDouble() }
            .description("이 인스턴스에 붙어 있는 STOMP 세션 수").register(registry)
    }

    @EventListener
    fun onConnected(@Suppress("UNUSED_PARAMETER") e: SessionConnectedEvent) { active.incrementAndGet() }

    @EventListener
    fun onDisconnected(@Suppress("UNUSED_PARAMETER") e: SessionDisconnectEvent) {
        // DISCONNECT 프레임과 전송 종료가 둘 다 이벤트를 낼 수 있어 0 아래로 내려가지 않게 막는다
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun current(): Int = active.get()
}
