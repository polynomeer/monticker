package com.monticker.api.matching.saga

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface OrderSagaRepository : JpaRepository<OrderSaga, UUID> {

    @Query("""
        SELECT s FROM OrderSaga s
        WHERE s.status IN ('STARTED', 'COMPENSATING')
          AND s.startedAt < :before
    """)
    fun findIncomplete(before: Instant): List<OrderSaga>
}
