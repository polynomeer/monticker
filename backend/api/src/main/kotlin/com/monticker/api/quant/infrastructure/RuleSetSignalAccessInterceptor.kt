package com.monticker.api.quant.infrastructure

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component
import java.security.Principal

private class StompUserPrincipal(val userId: Long) : Principal {
    override fun getName(): String = userId.toString()
}

/**
 * ADR-035 — /topic/rulesets/{id}/signals는 유료 구독 신호다. WebSocketConfig의 STOMP
 * 브로커는 원래 인증이 전혀 없어(ChannelInterceptor 없음) 룰셋 ID만 알면 누구나 구독해
 * 신호를 받을 수 있었다 — 소유자 또는 실제 구독자만 이 특정 목적지를 구독하게 막는다.
 * 다른 공개 토픽(/topic/stocks/{id} 등)은 이 인터셉터가 건드리지 않는다 — CONNECT는
 * 토큰이 없어도 항상 허용하고, 보호 대상 목적지의 SUBSCRIBE에서만 판단한다.
 */
@Component
class RuleSetSignalAccessInterceptor(
    private val jwtTokenProvider: JwtTokenProvider,
    private val ruleSetRepository: RuleSetRepository,
    private val jdbc: JdbcTemplate,
) : ChannelInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)
    private val signalDestination = Regex("^/topic/rulesets/([^/]+)/signals$")

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        when (accessor.command) {
            StompCommand.CONNECT -> {
                val token = accessor.getFirstNativeHeader("Authorization")?.removePrefix("Bearer ")?.trim()
                if (!token.isNullOrBlank() && jwtTokenProvider.validateToken(token)) {
                    accessor.user = StompUserPrincipal(jwtTokenProvider.getUserId(token))
                }
                // 토큰이 없거나 무효해도 연결은 허용한다 — 가격 등 다른 공개 토픽은 로그인이 필요 없다.
            }
            StompCommand.SUBSCRIBE -> {
                val destination = accessor.destination ?: return message
                val rulesetId = signalDestination.find(destination)?.groupValues?.get(1) ?: return message
                val userId = (accessor.user as? StompUserPrincipal)?.userId
                    ?: throw IllegalStateException("로그인 후 구독할 수 있습니다.")
                if (!hasAccess(rulesetId, userId)) {
                    log.warn("[RuleSetSignalAccess] 접근 거부: userId={} rulesetId={}", userId, rulesetId)
                    throw IllegalStateException("이 전략을 구독해야 신호를 받을 수 있습니다.")
                }
            }
            else -> {}
        }
        return message
    }

    private fun hasAccess(rulesetId: String, userId: Long): Boolean {
        if (ruleSetRepository.findByIdAndUserId(rulesetId, userId).isPresent) return true
        val marketId = jdbc.query(
            "SELECT id FROM strategy_market WHERE ruleset_id = ?",
            { rs, _ -> rs.getLong("id") }, rulesetId,
        ).firstOrNull() ?: return false
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM strategy_subscriptions WHERE market_id = ? AND user_id = ?",
            Long::class.java, marketId, userId,
        ) ?: 0L
        return count > 0
    }
}
