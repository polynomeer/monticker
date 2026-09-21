package com.monticker.api.auth.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * H3 — refresh token 재사용 감지 시 전체 세션 폐기. AuthService.refresh()는 재사용을
 * 발견하면 곧바로 IllegalArgumentException을 던져 호출자에게 401을 반환하는데, AuthService가
 * 클래스 레벨 @Transactional이라 같은 트랜잭션 안에서 실행한 폐기 DELETE가 그 예외로 함께
 * 롤백돼버린다(RiskCheckAuditLogger와 같은 이유로 REQUIRES_NEW가 필요 — 최초 구현에서 실제로
 * 이 롤백 때문에 다른 세션이 폐기되지 않는 버그가 났다, 라이브 curl 재현으로 확인).
 */
@Service
class RefreshTokenRevocationService(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeAll(userId: Long) {
        val revoked = jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId)
        if (revoked > 0) {
            log.warn("[RefreshTokenRevocationService] refresh token 재사용 의심 — 전체 세션 폐기: userId={} revoked={}", userId, revoked)
        }
    }
}
