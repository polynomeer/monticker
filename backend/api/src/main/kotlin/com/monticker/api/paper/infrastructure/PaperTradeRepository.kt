package com.monticker.api.paper.infrastructure
import com.monticker.api.paper.domain.PaperTrade
import org.springframework.data.jpa.repository.JpaRepository
interface PaperTradeRepository : JpaRepository<PaperTrade, Long> {
    fun findTop20ByUserIdOrderByTradedAtDesc(userId: Long): List<PaperTrade>
    fun findByFillId(fillId: Long): PaperTrade?
}
