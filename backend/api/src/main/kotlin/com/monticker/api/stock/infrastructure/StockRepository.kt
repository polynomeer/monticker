package com.monticker.api.stock.infrastructure

import com.monticker.api.stock.domain.Stock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface StockRepository : JpaRepository<Stock, Long> {

    @Query("""
        SELECT s FROM Stock s
        WHERE s.isActive = true
          AND (
            LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(s.symbol) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        ORDER BY
          CASE WHEN LOWER(s.symbol) = LOWER(:query) THEN 0
               WHEN LOWER(s.name) = LOWER(:query) THEN 1
               ELSE 2
          END,
          s.name
    """)
    fun searchByNameOrSymbol(query: String): List<Stock>
}
