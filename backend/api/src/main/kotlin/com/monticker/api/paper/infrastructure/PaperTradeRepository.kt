package com.monticker.api.paper.infrastructure
import com.monticker.api.paper.domain.PaperTrade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
interface PaperTradeRepository : JpaRepository<PaperTrade, Long> {
    fun findTop20ByUserIdOrderByTradedAtDesc(userId: Long): List<PaperTrade>

    @Query("""
        SELECT t.stockId, SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE -t.quantity END) AS qty,
               SUM(CASE WHEN t.side='BUY' THEN t.amount ELSE 0 END) /
               NULLIF(SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE 0 END), 0) AS avgPrice
        FROM PaperTrade t WHERE t.userId = :userId
        GROUP BY t.stockId
        HAVING SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE -t.quantity END) > 0
    """)
    fun findHoldings(@Param("userId") userId: Long): List<Array<Any>>
}
