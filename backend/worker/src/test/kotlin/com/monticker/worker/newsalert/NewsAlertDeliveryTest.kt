package com.monticker.worker.newsalert

import com.monticker.worker.notification.UserNotificationDispatcher
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** 뉴스·공시 알림 운영 스위치 — 끄면 이미 쌓인 발송 기록도 디스패처로 넘기지 않는다. */
class NewsAlertDeliveryTest {

    private val dispatcher = mockk<UserNotificationDispatcher>(relaxed = true)
    private val event = NewsAlertNotifyEvent(userId = 7L, historyId = 11L, title = "삼성전자 새 뉴스", body = "실적 발표", dedupKey = "news:1:u7")

    @Test
    fun `스위치가 켜져 있으면 디스패처로 보낸다`() {
        NewsAlertDelivery(dispatcher, enabled = true).on(event)

        verify(exactly = 1) { dispatcher.dispatch(event.toMessage()) }
    }

    @Test
    fun `스위치를 끄면 보내지 않는다`() {
        NewsAlertDelivery(dispatcher, enabled = false).on(event)

        verify(exactly = 0) { dispatcher.dispatch(any()) }
    }
}
