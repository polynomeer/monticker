package com.monticker.api.subscription.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** ADR-109 — 탈퇴하면 빌링키를 지우고 활성 구독을 해지한다([SubscriptionService.closeForAccountDeletion]). 탈퇴 트랜잭션 안에서 동기로 돈다. */
@Component
class SubscriptionUserDeletionListener(private val subscriptions: SubscriptionService) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        subscriptions.closeForAccountDeletion(event.userId)
    }
}
