package com.monticker.worker.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import com.monticker.worker.push.PushResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/** ADR-065 — api가 보낸 사용자 알림을 한 번만, 닿는 채널로 보낸다. */
class UserNotificationDispatcherTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val push = mockk<ExpoPushSender>()
    private val ops = mockk<ValueOperations<String, String>>()
    private val redis = mockk<StringRedisTemplate> {
        every { opsForValue() } returns ops
        every { delete(any<String>()) } returns true
    }
    private val mail = mockk<JavaMailSender>(relaxed = true)
    private val prefs = mockk<NotificationPreferenceReader>()
    private val dispatcher = UserNotificationDispatcher(jdbc, push, redis, mail, prefs)

    private val msg = UserNotificationMessage(
        userId = 7L, title = "005930 손절(스탑로스) 주문이 실행되지 않았습니다", body = "본문",
        dedupKey = "conditional-order-failed:12", data = mapOf("conditionalOrderId" to 12),
    )

    private fun firstDelivery(first: Boolean = true) {
        every { ops.setIfAbsent("notify:user:sent:conditional-order-failed:12", "1", any()) } returns first
    }
    private fun tokens(vararg t: String) {
        every { jdbc.queryForList(match<String> { it.contains("device_tokens") }, String::class.java, 7L) } returns t.toList()
    }
    private fun email(address: String?) {
        every { jdbc.queryForList(match<String> { it.contains("FROM users") }, String::class.java, 7L) } returns listOfNotNull(address)
    }

    @Test
    fun `등록 기기로 푸시를 보낸다`() {
        firstDelivery(); tokens("tok-a", "tok-b")
        val sent = slot<List<PushMessage>>()
        every { push.send(capture(sent)) } returns listOf(PushResult("tok-a", "ok", null), PushResult("tok-b", "ok", null))

        dispatcher.dispatch(msg)

        assertThat(sent.captured.map { it.to }).containsExactly("tok-a", "tok-b")
        assertThat(sent.captured.first().title).isEqualTo(msg.title)
        assertThat(sent.captured.first().data).containsEntry("conditionalOrderId", 12)
        verify(exactly = 0) { mail.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `같은 사건이 다시 오면 보내지 않는다 — 아웃박스 재전송·Kafka 재시도`() {
        firstDelivery(first = false)

        dispatcher.dispatch(msg)

        verify(exactly = 0) { push.send(any()) }
        verify(exactly = 0) { mail.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `등록 기기가 없으면 이메일로 보낸다`() {
        firstDelivery(); tokens(); email("u@test.local")
        val sent = slot<SimpleMailMessage>()
        every { mail.send(capture(sent)) } returns Unit

        dispatcher.dispatch(msg)

        assertThat(sent.captured.to).containsExactly("u@test.local")
        assertThat(sent.captured.subject).contains(msg.title)
    }

    @Test
    fun `푸시가 어느 기기에도 닿지 않으면(서킷 OPEN 포함) 이메일로 보낸다`() {
        firstDelivery(); tokens("tok-a"); email("u@test.local")
        every { push.send(any()) } returns emptyList()

        dispatcher.dispatch(msg)

        verify(exactly = 1) { mail.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `발송이 예외로 실패하면 중복 표시를 지워 재시도가 다시 보낼 수 있게 한다`() {
        firstDelivery(); tokens(); email("u@test.local")
        every { mail.send(any<SimpleMailMessage>()) } throws org.springframework.mail.MailSendException("smtp down")

        assertThatThrownBy { dispatcher.dispatch(msg) }.isInstanceOf(org.springframework.mail.MailSendException::class.java)
        verify { redis.delete("notify:user:sent:conditional-order-failed:12") }
    }

    @Test
    fun `api가 외부화한 JSON을 읽는다`() {
        val json = """{"userId":7,"title":"t","body":"b","dedupKey":"k","data":{"type":"CONDITIONAL_ORDER_FAILED","conditionalOrderId":12}}"""

        val read = ObjectMapper().findAndRegisterModules().readValue(json, UserNotificationMessage::class.java)

        assertThat(read).isEqualTo(UserNotificationMessage(7L, "t", "b", "k", mapOf("type" to "CONDITIONAL_ORDER_FAILED", "conditionalOrderId" to 12)))
    }

    // ── ADR-082 사용자 설정 ──────────────────────────────────────────────────

    private fun fills(dedup: String = "brokerage-order-filled:3") =
        UserNotificationMessage(userId = 7L, title = "체결", body = "본문", dedupKey = dedup, category = "FILLS")

    @Test
    fun `끌 수 없는 종류(분류 없음 포함)는 설정을 읽지 않는다`() {
        firstDelivery(); tokens("tok-a")
        every { push.send(any()) } returns listOf(PushResult("tok-a", "ok", null))

        dispatcher.dispatch(msg)
        dispatcher.dispatch(msg.copy(category = "ORDER_OUTCOME"))

        verify(exactly = 0) { prefs.forUser(any()) }
    }

    @Test
    fun `체결 알림을 끈 사용자에게는 보내지 않고 중복 표시도 남기지 않는다`() {
        every { prefs.forUser(7L) } returns NotificationPreference(fillsPush = false, fillsEmail = false)

        dispatcher.dispatch(fills())

        verify(exactly = 0) { ops.setIfAbsent(any(), any(), any()) }
        verify(exactly = 0) { push.send(any()) }
        verify(exactly = 0) { mail.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `전체 알림을 끄면 끌 수 있는 종류는 모두 멈춘다`() {
        every { prefs.forUser(7L) } returns NotificationPreference(allEnabled = false)

        dispatcher.dispatch(fills())

        verify(exactly = 0) { push.send(any()) }
        verify(exactly = 0) { mail.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `이메일을 고른 종류는 푸시가 닿아도 이메일을 함께 보낸다`() {
        every { prefs.forUser(7L) } returns NotificationPreference(fillsPush = true, fillsEmail = true)
        every { ops.setIfAbsent("notify:user:sent:brokerage-order-filled:3", "1", any()) } returns true
        tokens("tok-a"); email("u@test.local")
        every { push.send(any()) } returns listOf(PushResult("tok-a", "ok", null))

        dispatcher.dispatch(fills())

        verify(exactly = 1) { push.send(any()) }
        verify(exactly = 1) { mail.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `전략 마켓 소식은 마케팅 동의가 없으면 설정을 켰어도 보내지 않는다`() {
        every { prefs.forUser(7L) } returns NotificationPreference(strategyMarketNewsPush = true, strategyMarketNewsEmail = true)
        every { prefs.marketingAgreed(7L) } returns false

        dispatcher.dispatch(UserNotificationMessage(7L, "새 전략", "본문", "sm:1", category = "STRATEGY_MARKET"))

        verify(exactly = 0) { push.send(any()) }
        verify(exactly = 0) { mail.send(any<SimpleMailMessage>()) }
    }

    // ── ADR-093 방해 금지 시간 ─────────────────────────────────────────────

    /** 지금(KST)을 가운데 둔 2시간 방해 금지 — 실행 시각과 무관하게 구간 안이다. */
    private fun quietNow() = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).withSecond(0).withNano(0).let {
        NotificationPreference(quietHoursEnabled = true, quietHoursStart = it.minusHours(1), quietHoursEnd = it.plusHours(1))
    }

    @Test
    fun `끌 수 없는 알림은 방해 금지 시간에도 즉시 푸시한다 - 설정을 읽지도 않는다`() {
        every { prefs.forUser(7L) } returns quietNow()
        firstDelivery(); tokens("tok-a")
        every { push.send(any()) } returns listOf(PushResult("tok-a", "ok", null))

        dispatcher.dispatch(msg.copy(category = "ORDER_OUTCOME"))
        dispatcher.dispatch(msg.copy(category = "RISK_WARNING"))

        verify(exactly = 2) { push.send(any()) }
        verify(exactly = 0) { prefs.forUser(any()) }
    }

    @Test
    fun `끌 수 있는 알림은 방해 금지 시간에 푸시하지 않고 대체 이메일도 보내지 않는다`() {
        every { prefs.forUser(7L) } returns quietNow()

        dispatcher.dispatch(msg.copy(category = "FILLS"))

        verify(exactly = 0) { push.send(any()) }
        verify(exactly = 0) { mail.send(any<SimpleMailMessage>()) }
        verify(exactly = 0) { ops.setIfAbsent(any(), any(), any<java.time.Duration>()) }
    }
}
