package com.monticker.api.watchrule.infrastructure

import com.monticker.api.watchrule.domain.WatchRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WatchRuleRepository : JpaRepository<WatchRule, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<WatchRule>

    /**
     * 이 종목의 이 이벤트에 걸린 활성 규칙 — 종목 규칙(부분 인덱스 idx_watch_rules_stock_event) +
     * **지금** 이 종목이 든 관심종목 그룹을 대상으로 한 규칙(ADR-095, idx_watch_rules_group_event).
     * 그룹은 규칙 소유자의 것이어야 한다 — 남의 그룹 id를 넣은 규칙이 있더라도 발동하지 않는다(생성 때도 404로 막는다).
     * 그룹 안 (그룹, 종목)은 유니크라(V3) 규칙 하나가 한 이벤트에 두 번 나오지 않는다.
     */
    @Query(
        value = WatchRuleQueries.ACTIVE_FOR_EVENT,
        nativeQuery = true,
    )
    fun findActiveForEvent(@Param("stockId") stockId: Long, @Param("eventType") eventType: String): List<WatchRule>

    /** ADR-077 전략 신호 규칙 — 종목 규칙(idx_watch_rules_quant_signal) + 이 종목이 든 내 그룹 규칙(ADR-095). */
    @Query(
        value = WatchRuleQueries.ACTIVE_FOR_SIGNAL,
        nativeQuery = true,
    )
    fun findActiveForSignal(@Param("stockId") stockId: Long, @Param("ruleSetId") ruleSetId: String): List<WatchRule>
}

/** 네이티브 쿼리 본문 — 통합 테스트가 같은 SQL을 실제 Postgres에 직접 실행해 검증한다(스프링 컨텍스트 없이). */
object WatchRuleQueries {
    const val ACTIVE_FOR_EVENT = """
            SELECT r.* FROM watch_rules r
            WHERE r.is_active AND r.target_type = 'STOCK' AND r.stock_id = :stockId AND r.event_type = :eventType
            UNION ALL
            SELECT r.* FROM watch_rules r
            WHERE r.is_active AND r.target_type = 'GROUP' AND r.event_type = :eventType
              AND EXISTS (
                  SELECT 1 FROM watchlist_items i JOIN watchlist_groups g ON g.id = i.group_id
                  WHERE i.group_id = r.target_group_id AND i.stock_id = :stockId AND g.user_id = r.user_id
              )
        """

    const val ACTIVE_FOR_SIGNAL = """
            SELECT r.* FROM watch_rules r
            WHERE r.is_active AND r.event_type = 'QUANT_SIGNAL' AND r.rule_set_id = :ruleSetId
              AND r.target_type = 'STOCK' AND r.stock_id = :stockId
            UNION ALL
            SELECT r.* FROM watch_rules r
            WHERE r.is_active AND r.event_type = 'QUANT_SIGNAL' AND r.rule_set_id = :ruleSetId
              AND r.target_type = 'GROUP'
              AND EXISTS (
                  SELECT 1 FROM watchlist_items i JOIN watchlist_groups g ON g.id = i.group_id
                  WHERE i.group_id = r.target_group_id AND i.stock_id = :stockId AND g.user_id = r.user_id
              )
        """
}
