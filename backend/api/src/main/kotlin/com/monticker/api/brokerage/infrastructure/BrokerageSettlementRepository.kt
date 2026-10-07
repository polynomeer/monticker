package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/** ADR-086 정산일 재정렬 대상 — 정산 id, 지금 정산일, 정산 행을 만든 시각(체결 확인 시각, 예전 계산의 기준일) */
data class PendingBrokerageSettlementDate(val id: Long, val settleDate: LocalDate, val createdAt: Instant)

interface BrokerageSettlementRepository : JpaRepository<BrokerageSettlement, Long> {
    fun findAllByUserIdOrderBySettleDateDesc(userId: Long, pageable: Pageable): Page<BrokerageSettlement>
    fun findAllByUserIdAndStatus(userId: Long, status: BrokerageSettlementStatus): List<BrokerageSettlement>

    @Query("""
        SELECT s FROM BrokerageSettlement s
        WHERE s.status = 'PENDING' AND s.settleDate <= :today AND s.id > :afterId
        ORDER BY s.id ASC
    """)
    fun findDueSettlementsAfter(
        @Param("today") today: LocalDate,
        @Param("afterId") afterId: Long,
        pageable: Pageable,
    ): List<BrokerageSettlement>

    @Query("""
        SELECT new com.monticker.api.brokerage.infrastructure.PendingBrokerageSettlementDate(s.id, s.settleDate, s.createdAt)
        FROM BrokerageSettlement s
        WHERE s.status = 'PENDING' AND s.settleDate >= :today
        ORDER BY s.id
    """)
    fun findUpcomingPendingDates(@Param("today") today: LocalDate): List<PendingBrokerageSettlementDate>

    /** 읽은 뒤 바뀌지 않았을 때만 옮긴다 — 멱등, 파드 여러 개가 동시에 돌아도 안전 */
    @Modifying
    @Transactional
    @Query("""
        UPDATE BrokerageSettlement s SET s.settleDate = :newDate
        WHERE s.id = :id AND s.status = 'PENDING' AND s.settleDate = :oldDate
    """)
    fun moveSettleDate(@Param("id") id: Long, @Param("oldDate") oldDate: LocalDate, @Param("newDate") newDate: LocalDate): Int
}
