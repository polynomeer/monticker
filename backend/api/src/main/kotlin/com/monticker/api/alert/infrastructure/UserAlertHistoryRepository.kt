package com.monticker.api.alert.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-090 — 규칙 없이 사용자에게 직접 붙는 알림 이력(alert_histories.user_id). 첫 사례는 퀀트 시그널.
 * 규칙 이력(rule_id)은 worker AlertDispatcher가 쓴다 — 한 행은 둘 중 한 길로만 주인을 갖는다(V87 CHECK).
 */
@Repository
class UserAlertHistoryRepository(private val jdbc: JdbcTemplate) {

    /**
     * (사용자, [dedupKey])당 한 행. 이미 있으면 아무것도 하지 않고 null — 아웃박스 재전달·동시 리스너가 같은 사건을
     * 두 번 써도 행도, 뒤따르는 알림·색인 이벤트도 한 번이다. 판정은 사전 조회가 아니라 유니크 인덱스가 한다.
     */
    fun insertIfAbsent(
        userId: Long,
        category: String,
        dedupKey: String,
        stockId: Long?,
        triggeredAt: Instant,
        message: String,
        deliveryStatus: String,
        payloadJson: String?,
    ): Long? = jdbc.queryForList(
        """
        INSERT INTO alert_histories (user_id, category, dedup_key, stock_id, triggered_at, message, payload_json, delivery_status)
        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
        ON CONFLICT (user_id, dedup_key) WHERE user_id IS NOT NULL DO NOTHING
        RETURNING id
        """.trimIndent(),
        Long::class.java,
        userId, category, dedupKey, stockId, Timestamp.from(triggeredAt), message, payloadJson, deliveryStatus,
    ).firstOrNull()
}
