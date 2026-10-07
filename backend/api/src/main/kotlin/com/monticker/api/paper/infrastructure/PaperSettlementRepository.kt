package com.monticker.api.paper.infrastructure

import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.SettlementStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 정산일 재정렬 대상 — 정산 id, 지금 정산일, 체결 시각 */
data class PendingSettlementDate(val id: Long, val settleDate: LocalDate, val tradedAt: Instant)

/** 정산일·상태별 순액(매수 -, 매도 +)과 건수 */
data class SettlementDayStatusSum(
    val settleDate: LocalDate,
    val status: SettlementStatus,
    val signedNet: BigDecimal,
    val count: Long,
)

interface PaperSettlementRepository : JpaRepository<PaperSettlement, Long> {

    fun findAllByUserIdOrderBySettleDateDesc(userId: Long, pageable: Pageable): Page<PaperSettlement>

    fun findAllByUserIdAndStatus(userId: Long, status: SettlementStatus): List<PaperSettlement>

    fun findAllByUserIdAndStatusOrderBySettleDateDesc(userId: Long, status: SettlementStatus, pageable: Pageable): Page<PaperSettlement>

    /** ADR-086 — 기간별 정산 집계(이번 주 순액 등). */
    @Query("""
        SELECT new com.monticker.api.paper.infrastructure.SettlementDayStatusSum(
            s.settleDate, s.status,
            SUM(CASE WHEN s.side = 'BUY' THEN (0 - s.netAmount) ELSE s.netAmount END),
            COUNT(s))
        FROM PaperSettlement s
        WHERE s.userId = :userId AND s.settleDate BETWEEN :from AND :to
        GROUP BY s.settleDate, s.status
    """)
    fun sumByDateAndStatus(
        @Param("userId") userId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<SettlementDayStatusSum>

    fun findByTradeId(tradeId: Long): PaperSettlement?

    @Query("""
        SELECT s FROM PaperSettlement s
        WHERE s.status = 'PENDING' AND s.settleDate <= :today AND s.id > :afterId
        ORDER BY s.id ASC
    """)
    fun findDueSettlementsAfter(
        @Param("today") today: LocalDate,
        @Param("afterId") afterId: Long,
        pageable: Pageable,
    ): List<PaperSettlement>

    /** ADR-086 — 아직 오지 않은(오늘 포함) PENDING 정산의 정산일과 체결 시각. */
    @Query("""
        SELECT new com.monticker.api.paper.infrastructure.PendingSettlementDate(s.id, s.settleDate, t.tradedAt)
        FROM PaperSettlement s, PaperTrade t
        WHERE t.id = s.tradeId AND s.status = 'PENDING' AND s.settleDate >= :today
        ORDER BY s.id
    """)
    fun findUpcomingPendingDates(@Param("today") today: LocalDate): List<PendingSettlementDate>

    /** 읽은 뒤 바뀌지 않았을 때만 옮긴다(여러 파드가 동시에 돌아도, 그 사이 정산돼도 안전). 바뀐 행 수를 돌려준다. */
    @Modifying
    @Transactional
    @Query("""
        UPDATE PaperSettlement s SET s.settleDate = :newDate
        WHERE s.id = :id AND s.status = 'PENDING' AND s.settleDate = :oldDate
    """)
    fun moveSettleDate(@Param("id") id: Long, @Param("oldDate") oldDate: LocalDate, @Param("newDate") newDate: LocalDate): Int
}
