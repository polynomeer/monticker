package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.ConditionalOrder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConditionalOrderRepository : JpaRepository<ConditionalOrder, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<ConditionalOrder>
    fun findAllByOcoGroupId(ocoGroupId: UUID): List<ConditionalOrder>
}
