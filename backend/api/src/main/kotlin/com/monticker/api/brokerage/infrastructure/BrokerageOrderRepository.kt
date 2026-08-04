package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface BrokerageOrderRepository : JpaRepository<BrokerageOrder, Long> {
    fun findAllByUserIdOrderBySubmittedAtDesc(userId: Long, pageable: Pageable): Page<BrokerageOrder>
    fun findAllByAccountIdAndStatus(accountId: Long, status: BrokerageOrderStatus): List<BrokerageOrder>
    fun findByPgOrderId(pgOrderId: String): BrokerageOrder?
}
