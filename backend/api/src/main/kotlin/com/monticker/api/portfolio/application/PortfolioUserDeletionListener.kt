package com.monticker.api.portfolio.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 회원 탈퇴 시 통합 포트폴리오 데이터(계좌·거래·가져오기 기록)를 지우고 동의 철회를 기록한다. 탈퇴는 사용자 행을 남기는
 * 소프트 삭제라 users ON DELETE CASCADE가 동작하지 않는다. 탈퇴 트랜잭션 안에서 동기로 돈다(같이 커밋·롤백).
 * "계좌 정보 수집·이용" 동의 문구의 보관 기간("탈퇴하면 바로 지운다")이 이것에 기댄다.
 */
@Component
class PortfolioUserDeletionListener(private val accounts: PortfolioAccountService) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        accounts.deleteAll(event.userId)
    }
}
