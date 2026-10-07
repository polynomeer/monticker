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

    /** 월(KST)별 순수익 — 취소분 제외. 행: [ym 'YYYY-MM', sum] */
    @Query(
        value = """
            SELECT to_char(earned_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM') AS ym, SUM(net_amount) AS total
            FROM creator_earnings
            WHERE creator_id = :creatorId AND status <> 'CANCELLED' AND earned_at >= :since
            GROUP BY ym
            ORDER BY ym
        """,
        nativeQuery = true,
    )
    fun sumMonthlyNet(@Param("creatorId") creatorId: Long, @Param("since") since: java.time.Instant): List<Array<Any>>

    /** 전략별 누적·기간 순수익 — 취소분 제외. 행: [strategy_id, total, recent] */
    @Query(
        value = """
            SELECT strategy_id,
                   SUM(net_amount) AS total,
                   SUM(CASE WHEN earned_at >= :since THEN net_amount ELSE 0 END) AS recent
            FROM creator_earnings
            WHERE creator_id = :creatorId AND status <> 'CANCELLED'
            GROUP BY strategy_id
        """,
        nativeQuery = true,
    )
    fun sumNetByStrategy(@Param("creatorId") creatorId: Long, @Param("since") since: java.time.Instant): List<Array<Any>>

    fun existsByStrategyIdAndSubscriberId(strategyId: Long, subscriberId: Long): Boolean

    fun findAllByCreatorIdAndStatus(creatorId: Long, status: EarningStatus): List<CreatorEarning>
}
