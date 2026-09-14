package com.monticker.api.common.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent

class WebSocketConnectionGaugeTest {

    @Test
    fun `연결과 해제를 세고, 중복 해제 이벤트에도 음수가 되지 않는다`() {
        val registry = SimpleMeterRegistry()
        val gauge = WebSocketConnectionGauge(registry)

        repeat(3) { gauge.onConnected(mockk<SessionConnectedEvent>()) }
        assertThat(registry.get("ws_active_connections").gauge().value()).isEqualTo(3.0)

        repeat(5) { gauge.onDisconnected(mockk<SessionDisconnectEvent>()) }   // 3개 연결에 5번 해제
        assertThat(registry.get("ws_active_connections").gauge().value()).isEqualTo(0.0)
    }
}
