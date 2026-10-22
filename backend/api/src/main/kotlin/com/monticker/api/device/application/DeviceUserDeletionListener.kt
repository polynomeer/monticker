package com.monticker.api.device.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-114 — 탈퇴하면 푸시 토큰을 지운다. 비활성화(로그아웃)와 달리 행을 남길 이유가 없다 — 토큰은 그 기기로 알림을 보내는 수단이고,
 * 남겨 두면 같은 기기로 다른 계정이 로그인할 때까지 떠난 사용자 앞으로 쌓인 알림이 나갈 수 있다. 탈퇴 트랜잭션 안에서 동기로 돈다.
 */
@Component
class DeviceUserDeletionListener(private val jdbc: JdbcTemplate) {

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        jdbc.update("DELETE FROM device_tokens WHERE user_id = ?", event.userId)
    }
}
