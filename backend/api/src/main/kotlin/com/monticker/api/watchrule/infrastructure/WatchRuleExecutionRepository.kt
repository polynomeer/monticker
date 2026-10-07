package com.monticker.api.watchrule.infrastructure

import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleExecutionStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface WatchRuleExecutionRepository : JpaRepository<WatchRuleExecution, Long> {

    fun existsByWatchRuleIdAndStockEventId(watchRuleId: Long, stockEventId: Long): Boolean

    fun existsByWatchRuleIdAndQuantSignalId(watchRuleId: Long, quantSignalId: Long): Boolean

    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): List<WatchRuleExecution>

    /**
     * 쿨다운 판정 — 실제로 주문이 나간 발동만 센다. SKIPPED/REJECTED 는 자금이 움직이지 않았으므로
     * 다음 기회를 막을 이유가 없다.
     */
    @Query(
        """
        SELECT COUNT(e) > 0 FROM WatchRuleExecution e
        WHERE e.watchRuleId = :ruleId AND e.status = :status AND e.createdAt > :after
        """
    )
    fun existsSince(ruleId: Long, status: WatchRuleExecutionStatus, after: Instant): Boolean
}
