package com.monticker.api.watchrule.infrastructure

import com.monticker.api.watchrule.domain.WatchRuleExecution
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WatchRuleExecutionRepository : JpaRepository<WatchRuleExecution, Long> {

    fun existsByWatchRuleIdAndStockEventId(watchRuleId: Long, stockEventId: Long): Boolean

    fun existsByWatchRuleIdAndQuantSignalId(watchRuleId: Long, quantSignalId: Long): Boolean

    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): List<WatchRuleExecution>
}
