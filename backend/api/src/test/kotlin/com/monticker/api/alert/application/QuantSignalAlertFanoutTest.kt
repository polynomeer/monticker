package com.monticker.api.alert.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.alert.infrastructure.UserAlertHistoryRepository
import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.common.search.SearchIndexEvent
import com.monticker.api.quant.application.SignalAudience
import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.quant.events.QuantSignalEmittedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.time.LocalDate

class QuantSignalAlertFanoutTest {

    private val access = mockk<StrategySignalAccess>()
    private val histories = mockk<UserAlertHistoryRepository>()
    private val published = mutableListOf<Any>()
    private val events = mockk<ApplicationEventPublisher> { every { publishEvent(any<Any>()) } answers { published += firstArg<Any>() } }
    private val fanout = QuantSignalAlertFanout(access, histories, events, ObjectMapper())

    private val event = QuantSignalEmittedEvent(
        signalId = 42, ruleSetId = "rs1", stockId = 5, direction = "BUY",
        signalTime = Instant.parse("2026-10-08T07:00:00Z"), price = 71_500.0, evalDate = LocalDate.of(2026, 10, 8),
    )

    private fun stubInsert(userId: Long, returns: Long?) {
        every { histories.insertIfAbsent(userId, "QUANT_SIGNAL", "quant-signal:42", 5L, event.signalTime, any(), "QUEUED", any()) } returns returns
    }

    @Test
    fun `owner and subscribers each get one history row, one index event and one QUANT_SIGNAL command`() {
        every { access.audienceOf("rs1") } returns SignalAudience("골든크로스", ownerId = 1, userIds = linkedSetOf(1L, 7L))
        stubInsert(1L, 100L)
        stubInsert(7L, 101L)

        assertThat(fanout.fanOut(event)).isEqualTo(2)

        val commands = published.filterIsInstance<UserNotificationCommand>()
        assertThat(commands.map { it.userId }).containsExactly(1L, 7L)
        assertThat(commands).allSatisfy { assertThat(it.category).isEqualTo(NotificationCategory.QUANT_SIGNAL) }
        // worker의 사건 단위 중복 제거 키는 사용자마다 달라야 한다 — 같으면 구독자 알림이 주인 알림에 묻힌다
        assertThat(commands.map { it.dedupKey }).containsExactly("quant-signal:42:u1", "quant-signal:42:u7")
        assertThat(commands[0].body).startsWith("포워드 테스트에서")
        assertThat(commands[1].body).startsWith("구독 중인 전략에서")
        assertThat(commands[0].title).isEqualTo("골든크로스 매수 신호")

        val docs = published.filterIsInstance<SearchIndexEvent>()
        assertThat(docs.map { it.docId }).containsExactly("100", "101")
        assertThat(docs).allSatisfy {
            assertThat(it.index).isEqualTo("alert_histories")
            assertThat(it.payload!!["ruleType"]).isEqualTo("QUANT_SIGNAL")
            assertThat(it.payload!!["ruleId"]).isNull()
        }
        assertThat(docs[1].payload!!["userId"]).isEqualTo(7L)
    }

    @Test
    fun `redelivery writes nothing new and publishes nothing`() {
        every { access.audienceOf("rs1") } returns SignalAudience("골든크로스", ownerId = 1, userIds = linkedSetOf(1L, 7L))
        stubInsert(1L, null)
        stubInsert(7L, null)

        assertThat(fanout.fanOut(event)).isZero()
        assertThat(published).isEmpty()
    }

    @Test
    fun `a deleted ruleset reaches nobody`() {
        every { access.audienceOf("rs1") } returns null

        assertThat(fanout.fanOut(event)).isZero()
        verify(exactly = 0) { histories.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(published).isEmpty()
    }

    @Test
    fun `message carries strategy, side, close and date`() {
        every { access.audienceOf("rs1") } returns SignalAudience("모멘텀", ownerId = 1, userIds = linkedSetOf(1L))
        val sell = event.copy(direction = "SELL")
        every { histories.insertIfAbsent(1L, any(), any(), any(), any(), any(), any(), any()) } returns 9L

        fanout.fanOut(sell)

        verify { histories.insertIfAbsent(1L, "QUANT_SIGNAL", "quant-signal:42", 5L, sell.signalTime, "모멘텀 매도 신호 (종가 71,500원, 2026-10-08)", "QUEUED", any()) }
    }

    @Test
    fun `with the ops switch off history rows and index events are still written but no notification command`() {
        val switchedOff = QuantSignalAlertFanout(access, histories, events, ObjectMapper(), pushEnabled = false)
        every { access.audienceOf("rs1") } returns SignalAudience("골든크로스", ownerId = 1, userIds = linkedSetOf(1L, 7L))
        stubInsert(1L, 100L)
        stubInsert(7L, 101L)

        assertThat(switchedOff.fanOut(event)).isEqualTo(2)

        assertThat(published.filterIsInstance<SearchIndexEvent>().map { it.docId }).containsExactly("100", "101")
        assertThat(published.filterIsInstance<UserNotificationCommand>()).isEmpty()
    }
}
