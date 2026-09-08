package com.monticker.api.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * ADR-035 — 목적지별 STOMP 접근 제어가 필요한 모듈(quant의 RuleSetSignalAccessInterceptor 등)이
 * ChannelInterceptor 빈만 등록하면 여기 자동으로 연결된다. common이 quant를 직접 import하지
 * 않고도(Spring Modulith 경계, ADR-019) 런타임 DI로만 연결되는 구조다.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val channelInterceptors: List<ChannelInterceptor>,
) : WebSocketMessageBrokerConfigurer {

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
}
