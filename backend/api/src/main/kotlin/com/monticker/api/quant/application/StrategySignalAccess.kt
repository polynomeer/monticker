package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * ADR-035 — 전략 신호를 볼 수 있는 사람: 룰셋 소유자 또는 그 마켓 전략의 구독자.
 * RuleSetSignalAccessInterceptor(WS 구독)와 같은 기준을 다른 모듈(watchrule ADR-077, alert ADR-090)도 쓰게 노출한다.
 */
@Service
class StrategySignalAccess(
    private val ruleSetRepository: RuleSetRepository,
    private val jdbc: JdbcTemplate,
) {
    fun canAccess(userId: Long, ruleSetId: String): Boolean {
        if (ruleSetRepository.findByIdAndUserId(ruleSetId, userId).isPresent) return true
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM strategy_subscriptions ss
            JOIN strategy_market sm ON sm.id = ss.market_id
            WHERE sm.ruleset_id = ? AND ss.user_id = ?
            """.trimIndent(),
            Long::class.java, ruleSetId, userId,
        ) ?: 0L
        return count > 0
    }

    /** 규칙 카드 표시용 전략 이름. 접근 판정이 아니다. */
    fun nameOf(ruleSetId: String): String? = ruleSetRepository.findById(ruleSetId).orElse(null)?.name

    /**
     * ADR-090 — 지금 이 전략의 신호를 볼 수 있는 사람 전부([canAccess]와 같은 기준을 한 번에): 주인 + 구독자.
     * 룰셋이 없으면(삭제) 아무도 없다 — 구독 행이 남아 있어도 신호를 낼 전략이 아니다. 탈퇴한 사용자는 뺀다.
     */
    fun audienceOf(ruleSetId: String): SignalAudience? {
        val doc = ruleSetRepository.findById(ruleSetId).orElse(null) ?: return null
        val subscribers = subscriberIds(ruleSetId)
        return SignalAudience(
            ruleSetName = doc.name,
            ownerId = doc.userId,
            userIds = (listOf(doc.userId) + subscribers).toCollection(LinkedHashSet()),
        )
    }

    internal fun subscriberIds(ruleSetId: String): List<Long> = jdbc.queryForList(
        """
        SELECT ss.user_id FROM strategy_subscriptions ss
        JOIN strategy_market sm ON sm.id = ss.market_id
        JOIN users u ON u.id = ss.user_id AND u.deleted_at IS NULL
        WHERE sm.ruleset_id = ?
        ORDER BY ss.user_id
        """.trimIndent(),
        Long::class.java, ruleSetId,
    )
}

/** ADR-090 — 신호 하나를 받을 사람들. [userIds]는 주인이 먼저, 중복 없음. */
data class SignalAudience(
    val ruleSetName: String,
    val ownerId: Long,
    val userIds: Set<Long>,
)
