package com.monticker.api.paper.infrastructure

import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.SettlementStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface PaperSettlementRepository : JpaRepository<PaperSettlement, Long> {

    fun findAllByUserIdOrderBySettleDateDesc(userId: Long, pageable: Pageable): Page<PaperSettlement>

    fun findAllByUserIdAndStatus(userId: Long, status: SettlementStatus): List<PaperSettlement>

    fun findByTradeId(tradeId: Long): PaperSettlement?

    @Query("""
        SELECT s FROM PaperSettlement s
        WHERE s.status = 'PENDING' AND s.settleDate <= :today
        ORDER BY s.settleDate ASC
    """)
    fun findDueSettlements(@Param("today") today: LocalDate, pageable: Pageable): Page<PaperSettlement>
}
