package com.monticker.worker.newsalert

import com.monticker.worker.notification.UserNotificationDispatcher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * ADR-100 — 팬아웃이 커밋한 뒤 사용자 한 명에게 뉴스·공시 알림을 보낸다. 발송 규칙(설정·채널·방해 금지 시간·중복 제거)은
 * notify.user 경로와 같은 [UserNotificationDispatcher]가 NEWS 종류로 적용한다.
 *
 * 실패하면 발행 기록이 미완료로 남아 재전송된다. 디스패처는 dedupKey(사건×사용자)로 중복을 막고, 발송 예외면 표시를 지워
 * 재전송이 다시 보낼 수 있게 한다.
 */
@Component
class NewsAlertDelivery(private val dispatcher: UserNotificationDispatcher) {

    @ApplicationModuleListener
    fun on(event: NewsAlertNotifyEvent) {
        dispatcher.dispatch(event.toMessage())
    }
}
