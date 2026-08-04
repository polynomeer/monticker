package com.monticker.api.settlement.creator.infrastructure

import com.monticker.api.settlement.creator.domain.CreatorEarning
import com.monticker.api.settlement.creator.domain.EarningStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface CreatorEarningRepository : JpaRepository<CreatorEarning, Long> {

    fun findAllByCreatorIdOrderByEarnedAtDesc(creatorId: Long, pageable: Pageable): Page<CreatorEarning>

    @Query("SELECT COALESCE(SUM(e.netAmount), 0) FROM CreatorEarning e WHERE e.creatorId = :creatorId AND e.status = 'AVAILABLE'")
    fun sumAvailableByCreatorId(@Param("creatorId") creatorId: Long): BigDecimal

    @Query("""
        SELECT e.strategyId, SUM(e.netAmount) AS total
        FROM CreatorEarning e
        WHERE e.creatorId = :creatorId
        GROUP BY e.strategyId
        ORDER BY total DESC
    """)
    fun findEarningsByStrategy(@Param("creatorId") creatorId: Long): List<Array<Any>>

    fun existsByStrategyIdAndSubscriberId(strategyId: Long, subscriberId: Long): Boolean

    fun findAllByCreatorIdAndStatus(creatorId: Long, status: EarningStatus): List<CreatorEarning>
}
