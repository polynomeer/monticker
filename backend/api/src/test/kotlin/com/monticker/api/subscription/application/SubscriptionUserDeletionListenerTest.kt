package com.monticker.api.subscription.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class SubscriptionUserDeletionListenerTest {

    @Test
    fun `탈퇴 이벤트를 받으면 빌링키·구독을 정리한다`() {
        val subscriptions = mockk<SubscriptionService>(relaxed = true)

        SubscriptionUserDeletionListener(subscriptions).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { subscriptions.closeForAccountDeletion(42L) }
    }
}
