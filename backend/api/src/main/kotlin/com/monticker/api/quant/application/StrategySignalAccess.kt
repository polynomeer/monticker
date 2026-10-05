package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * ADR-035 — 전략 신호를 볼 수 있는 사람: 룰셋 소유자 또는 그 마켓 전략의 구독자.
 * RuleSetSignalAccessInterceptor(WS 구독)와 같은 기준을 다른 모듈(watchrule, ADR-077)도 쓰게 노출한다.
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
}
