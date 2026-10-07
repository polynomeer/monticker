package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

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
}
