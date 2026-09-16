package com.monticker.api.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

/**
 * ADR-035 — 목적지별 STOMP 접근 제어가 필요한 모듈(quant의 RuleSetSignalAccessInterceptor 등)이
 * ChannelInterceptor 빈만 등록하면 여기 자동으로 연결된다. common이 quant를 직접 import하지
 * 않고도(Spring Modulith 경계, ADR-019) 런타임 DI로만 연결되는 구조다.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val channelInterceptors: List<ChannelInterceptor>,
    @Value("\${app.ws.send-time-limit-ms:2000}") private val sendTimeLimitMs: Int,
    @Value("\${app.ws.send-buffer-size-limit-bytes:262144}") private val sendBufferSizeLimit: Int,
    @Value("\${app.ws.outbound-core-pool-size:0}") private val outboundCorePoolSize: Int,
) : WebSocketMessageBrokerConfigurer {

    /**
     * M-001(c) / D-M1-04 — 읽지 않는 클라이언트(느린 소비자)에 대한 한도. Spring 기본값은 sendTimeLimit 10s, 버퍼 512KB 다.
     * 느린 연결 25개(clientOutboundChannel 스레드 20개보다 많다)가 각각 한 스레드를 최대 10초씩 붙잡자 **다른 모든 클라이언트**의
     * 시세가 p95 14–17s, p99 17–19s 멈췄다(3회 재현). 한도를 넘긴 세션은 다른 스레드의 다음 send 가 닫아 주므로, 한도가 짧을수록
     * 정상 클라이언트가 인질로 잡히는 시간이 짧다. 값은 운영 ConfigMap 으로 조정.
     */
    override fun configureWebSocketTransport(registry: WebSocketTransportRegistration) {
        registry.setSendTimeLimit(sendTimeLimitMs).setSendBufferSizeLimit(sendBufferSizeLimit)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS()
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(*channelInterceptors.toTypedArray())
    }

    /**
     * M-001(c) / D-M1-04 — 시세 발행 스레드. 기본값은 가용 코어 수(이 머신 10)라, 느린 소비자가 그만큼의 스레드를 붙잡으면
     * 정상 클라이언트에게 나갈 send 가 큐에 쌓인다. sendTimeLimit(§configureWebSocketTransport)만으로는 부족했다 — 느린 세션이
     * 3초 안에 닫혀도 정상 클라이언트가 p99 ~20s 밀렸다. app.ws.outbound-core-pool-size>0 이면 그 값으로 늘린다(0=Spring 기본).
     */
    override fun configureClientOutboundChannel(registration: ChannelRegistration) {
        if (outboundCorePoolSize > 0) {
            registration.taskExecutor().corePoolSize(outboundCorePoolSize).maxPoolSize(outboundCorePoolSize * 2)
        }
    }
}
