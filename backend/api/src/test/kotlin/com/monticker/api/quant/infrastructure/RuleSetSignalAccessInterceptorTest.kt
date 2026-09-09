package com.monticker.api.quant.infrastructure

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.quant.domain.RuleSetDocument
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.messaging.Message
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import java.util.Optional

class RuleSetSignalAccessInterceptorTest {

    private val jwtTokenProvider = mockk<JwtTokenProvider>()
    private val ruleSetRepository = mockk<RuleSetRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val interceptor = RuleSetSignalAccessInterceptor(jwtTokenProvider, ruleSetRepository, jdbc)

    private fun connectMessage(token: String?): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        if (token != null) accessor.addNativeHeader("Authorization", "Bearer $token")
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    // 실제 Spring STOMP 세션에서는 CONNECT에서 기록한 Principal이 세션에 남아 이후 프레임에
    // 자동으로 실린다 — 유닛 테스트에서는 그 세션 전파를 직접 흉내낸다. 이 인터셉터 자체의
    // 판단 로직만 검증하는 것이고, 진짜 STOMP 세션 동작은 브라우저로 라이브 검증한다.
    private fun subscribeMessage(destination: String, connected: Message<*>?): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        accessor.destination = destination
        if (connected != null) accessor.user = StompHeaderAccessor.wrap(connected).user
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    private fun doc(userId: Long) = RuleSetDocument(id = "rs1", userId = userId, name = "test", status = "BACKTESTED")

    @Test
    fun `CONNECT에 유효한 토큰이 있으면 principal이 세션에 기록된다`() {
        every { jwtTokenProvider.validateToken("good") } returns true
        every { jwtTokenProvider.getUserId("good") } returns 1L

        val result = interceptor.preSend(connectMessage("good"), mockk())

        assertThat(StompHeaderAccessor.wrap(result).user?.name).isEqualTo("1")
    }

    @Test
    fun `CONNECT에 토큰이 없어도 연결은 허용된다`() {
        val result = interceptor.preSend(connectMessage(null), mockk())
        assertThat(StompHeaderAccessor.wrap(result).user).isNull()
    }

    @Test
    fun `보호 대상이 아닌 목적지 구독은 그대로 통과한다`() {
        val sub = subscribeMessage("/topic/stocks/1", connected = null)
        assertThat(interceptor.preSend(sub, mockk())).isEqualTo(sub)
    }

    @Test
    fun `인증 없이 신호 토픽을 구독하면 거부된다`() {
        val sub = subscribeMessage("/topic/rulesets/rs1/signals", connected = null)
        assertThatThrownBy { interceptor.preSend(sub, mockk()) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `소유자는 신호 토픽을 구독할 수 있다`() {
        every { jwtTokenProvider.validateToken("owner") } returns true
        every { jwtTokenProvider.getUserId("owner") } returns 1L
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(doc(1L))

        // interceptor.preSend()를 실제로 거쳐야 CONNECT에서 기록된 principal이 반영된다 —
        // 원본 connectMessage()만 넘기면 아직 아무도 그 메시지를 처리하지 않은 상태다.
        val connected = interceptor.preSend(connectMessage("owner"), mockk())
        val sub = subscribeMessage("/topic/rulesets/rs1/signals", connected)

        assertThat(interceptor.preSend(sub, mockk())).isEqualTo(sub)
    }

    @Test
    fun `구독자는 신호 토픽을 구독할 수 있다`() {
        every { jwtTokenProvider.validateToken("subscriber") } returns true
        every { jwtTokenProvider.getUserId("subscriber") } returns 9L
        every { ruleSetRepository.findByIdAndUserId("rs1", 9L) } returns Optional.empty()
        every { jdbc.query("SELECT id FROM strategy_market WHERE ruleset_id = ?", any<RowMapper<Long>>(), "rs1") } returns listOf(1L)
        every { jdbc.queryForObject("SELECT COUNT(*) FROM strategy_subscriptions WHERE market_id = ? AND user_id = ?", Long::class.java, 1L, 9L) } returns 1L

        val connected = interceptor.preSend(connectMessage("subscriber"), mockk())
        val sub = subscribeMessage("/topic/rulesets/rs1/signals", connected)

        assertThat(interceptor.preSend(sub, mockk())).isEqualTo(sub)
    }

    @Test
    fun `구독하지 않은 사용자는 신호 토픽 구독이 거부된다`() {
        every { jwtTokenProvider.validateToken("stranger") } returns true
        every { jwtTokenProvider.getUserId("stranger") } returns 99L
        every { ruleSetRepository.findByIdAndUserId("rs1", 99L) } returns Optional.empty()
        every { jdbc.query("SELECT id FROM strategy_market WHERE ruleset_id = ?", any<RowMapper<Long>>(), "rs1") } returns emptyList()

        val connected = interceptor.preSend(connectMessage("stranger"), mockk())
        val sub = subscribeMessage("/topic/rulesets/rs1/signals", connected)

        assertThatThrownBy { interceptor.preSend(sub, mockk()) }.isInstanceOf(IllegalStateException::class.java)
    }
}
