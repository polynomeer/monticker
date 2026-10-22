package com.monticker.api.brokerage.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.exception.BusinessRuleException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrokerageUserDeletionListenerTest {

    private val brokerage = mockk<BrokerageService>()

    @Test
    fun `탈퇴 이벤트를 받으면 그 사용자의 증권사 연동 키를 지운다`() {
        every { brokerage.closeForAccountDeletion(42L) } returns 1

        BrokerageUserDeletionListener(brokerage).on(UserAccountDeletedEvent(42L))

        verify(exactly = 1) { brokerage.closeForAccountDeletion(42L) }
    }

    @Test
    fun `결과가 열린 실주문이 있어 거부되면 예외를 삼키지 않는다 - 탈퇴 트랜잭션이 롤백돼야 한다`() {
        every { brokerage.closeForAccountDeletion(42L) } throws BusinessRuleException("확인 중인 주문")

        assertThrows<BusinessRuleException> { BrokerageUserDeletionListener(brokerage).on(UserAccountDeletedEvent(42L)) }
    }
}
