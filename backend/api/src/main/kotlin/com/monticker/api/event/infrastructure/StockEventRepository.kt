package com.monticker.api.event.infrastructure

import com.monticker.api.event.domain.EventType
import com.monticker.api.event.domain.StockEvent
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface StockEventRepository : JpaRepository<StockEvent, Long> {

    @Query("""
        SELECT e FROM StockEvent e
        WHERE e.stockId = :stockId
          AND e.eventTime BETWEEN :from AND :to
        ORDER BY e.eventTime DESC
    """)
    fun findByStockIdAndTimeRange(
        stockId: Long,
        from: Instant,
        to: Instant,
    ): List<StockEvent>

    fun existsByStockIdAndEventTypeAndEventTimeBetween(
        stockId: Long,
        eventType: EventType,
        from: Instant,
        to: Instant,
    ): Boolean

    fun findTopByOrderByEventTimeDesc(pageable: Pageable): List<StockEvent>
}
