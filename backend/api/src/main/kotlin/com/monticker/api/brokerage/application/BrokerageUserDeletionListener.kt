package com.monticker.api.brokerage.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * ADR-114 — 탈퇴하면 증권사 연동 키를 지운다([BrokerageService.closeForAccountDeletion]). 결과가 열린 실주문이 있으면 거부하고,
 * 그 예외가 탈퇴 트랜잭션 전체를 롤백한다 — 키 없이 체결되면 정산·원장이 영영 맞지 않는다(ADR-067). 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class BrokerageUserDeletionListener(private val brokerage: BrokerageService) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        brokerage.closeForAccountDeletion(event.userId)
    }
}
