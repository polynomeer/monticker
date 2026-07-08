package com.monticker.api.matching.infrastructure

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface OrderRepository : JpaRepository<Order, Long> {
    fun findByUserIdAndStatusIn(userId: Long, statuses: List<OrderStatus>): List<Order>
    fun findByStockIdAndStatusIn(stockId: Long, statuses: List<OrderStatus>): List<Order>
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<Order>
    fun countByUserIdAndCreatedAtAfter(userId: Long, after: Instant): Long
}
