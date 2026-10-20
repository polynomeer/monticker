package com.monticker.api.portfolio.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** 탈퇴하면 통합 포트폴리오 데이터를 지우고 동의 철회를 기록한다(deleteAll이 둘 다 한다 — PortfolioImportIntegrationTest) */
class PortfolioUserDeletionListenerTest {

    @Test
    fun `탈퇴 이벤트를 받으면 그 사용자의 포트폴리오 데이터를 모두 지운다`() {
        val accounts = mockk<PortfolioAccountService> { every { deleteAll(42L) } returns 2 }

        PortfolioUserDeletionListener(accounts).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { accounts.deleteAll(42L) }
    }
}
