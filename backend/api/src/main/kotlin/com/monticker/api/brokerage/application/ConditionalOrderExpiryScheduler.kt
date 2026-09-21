package com.monticker.api.brokerage.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * V-L2 — ConditionalOrderService.create/createOco가 채우는 expiresAt을 실제로 소비하는
 * 쪽. 매일 한 번, 만료 시각이 지난 ACTIVE 조건부 주문을 EXPIRED로 원자적 UPDATE 한다
 * (ConditionalOrderEvaluator의 발동 처리와 같은 "WHERE status = 'ACTIVE'" 조건부 갱신
 * 패턴 — 그 사이 발동/취소된 행은 건드리지 않는다).
 */
@Component
class ConditionalOrderExpiryScheduler(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    fun expireStaleOrders() {
        val now = Timestamp.from(Instant.now())
        val expired = jdbc.update(
            """
            UPDATE conditional_orders
            SET status = 'EXPIRED', updated_at = ?
            WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?
            """.trimIndent(),
            now, now,
        )
        if (expired > 0) {
            log.info("[ConditionalOrderExpiryScheduler] 만료 처리: count={}", expired)
        }
    }
}
