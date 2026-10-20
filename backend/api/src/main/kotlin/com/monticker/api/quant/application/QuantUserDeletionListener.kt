package com.monticker.api.quant.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * ADR-109 — 탈퇴하면 Quant Lab 데이터를 지우고, 마켓에 올린 전략은 판매 중지한다. 탈퇴 트랜잭션 안에서 동기로 돈다.
 *
 * - **구독자로서.** 구독 행을 지우고 그 전략의 subscribe_count를 1씩 줄인다(구독 해지와 같다).
 * - **크리에이터로서.** strategy_market 행은 지우지 않는다 — creator_earnings(5년 보존)가 참조한다. delisted_at을 채워 마켓
 *   목록·검색·구독에서 빼고, 남의 구독을 모두 끝낸다(subscribe_count 0). 룰셋이 지워지니 신호를 낼 수 없기 때문이다.
 *   유료 구독은 아직 닫혀 있어(ADR-080) 환불할 것이 없다.
 * - **룰셋과 그 산출물.** quant_signals·quant_forward_tests(+equity CASCADE)·quant_backtest_results는 ruleset_id(VARCHAR, FK 없음)로
 *   지운다. 대상 id는 Mongo의 내 룰셋과 내 마켓 행의 ruleset_id를 합친 것이다(Mongo 문서가 먼저 지워진 행도 정리한다).
 * - **Mongo 룰셋 문서**는 Postgres 트랜잭션에 묶이지 않는다. 커밋 뒤에 지운다 — 먼저 지웠다가 탈퇴가 롤백되면 탈퇴하지 않은 사용자의
 *   룰셋이 사라진다. 커밋 뒤 삭제가 실패하면 문서가 남지만 주인이 탈퇴해 아무도 볼 수 없다(로그를 남긴다).
 */
@Component
class QuantUserDeletionListener(
    private val jdbc: JdbcTemplate,
    private val ruleSetRepository: RuleSetRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val UNSUBSCRIBE_SQL = """
            WITH gone AS (DELETE FROM strategy_subscriptions WHERE user_id = ? RETURNING market_id)
            UPDATE strategy_market SET subscribe_count = GREATEST(subscribe_count - 1, 0)
            WHERE id IN (SELECT market_id FROM gone)
        """

        const val DELIST_SQL = """
            WITH delisted AS (
                UPDATE strategy_market SET delisted_at = now(), subscribe_count = 0
                WHERE user_id = ? AND delisted_at IS NULL
                RETURNING id
            )
            DELETE FROM strategy_subscriptions WHERE market_id IN (SELECT id FROM delisted)
        """

        const val MARKET_RULESET_IDS_SQL = "SELECT ruleset_id FROM strategy_market WHERE user_id = ?"
    }

    @EventListener
    fun on(event: UserAccountDeletedEvent) {
        val userId = event.userId
        val mongoIds = ruleSetRepository.findAllByUserId(userId).mapNotNull { it.id }
        val ruleSetIds = (mongoIds + jdbc.queryForList(MARKET_RULESET_IDS_SQL, String::class.java, userId).filterNotNull()).distinct()

        jdbc.update(UNSUBSCRIBE_SQL.trimIndent(), userId)
        jdbc.update(DELIST_SQL.trimIndent(), userId)

        if (ruleSetIds.isNotEmpty()) {
            val inList = ruleSetIds.joinToString(",") { "?" }
            val args = ruleSetIds.toTypedArray()
            // quant_signals.forward_test_id → quant_forward_tests(FK, CASCADE 없음)라 신호를 먼저 지운다.
            jdbc.update(
                "DELETE FROM quant_signals WHERE rule_set_id IN ($inList) " +
                    "OR forward_test_id IN (SELECT id FROM quant_forward_tests WHERE rule_set_id IN ($inList))",
                *args, *args,
            )
            jdbc.update("DELETE FROM quant_forward_tests WHERE rule_set_id IN ($inList)", *args)
            jdbc.update("DELETE FROM quant_backtest_results WHERE rule_set_id IN ($inList)", *args)
        }

        if (mongoIds.isNotEmpty()) afterCommit {
            runCatching { ruleSetRepository.deleteAllById(mongoIds) }
                .onFailure { log.error("[UserDeletion] rule set documents of userId={} were not deleted: {}", userId, mongoIds, it) }
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action(); return }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
