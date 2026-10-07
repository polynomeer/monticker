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

    // NOTE: deliberately NOT named findTopByOrderByEventTimeDesc — Spring Data's
    // "Top" keyword without a following digit means "limit 1" and takes priority
    // over the Pageable's page size, so a caller-supplied limit would silently be
    // ignored (found via StockEventRepositoryIntegrationTest hitting a real Postgres
    // instance; GET /api/events/recent always returned exactly 1 row regardless of
    // the requested `limit`). Plain findByOrderByEventTimeDesc lets Pageable fully
    // control both size and offset.
    fun findByOrderByEventTimeDesc(pageable: Pageable): List<StockEvent>

    /**
     * 종목별 가장 최근 이벤트 1건씩 — 종목마다 idx_stock_events_stock_time을 한 번 타는 LATERAL(종목 수만큼 인덱스 탐색 1번).
     * 이벤트가 없는 종목은 결과에 없다.
     */
    @Query(
        value = """
            SELECT e.* FROM stocks s
            CROSS JOIN LATERAL (
                SELECT se.* FROM stock_events se
                WHERE se.stock_id = s.id
                ORDER BY se.event_time DESC
                LIMIT 1
            ) e
            WHERE s.id IN (:stockIds)
        """,
        nativeQuery = true,
    )
    fun findLatestPerStock(stockIds: Collection<Long>): List<StockEvent>
}
