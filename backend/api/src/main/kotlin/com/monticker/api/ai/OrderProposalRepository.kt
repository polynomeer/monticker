package com.monticker.api.ai

import org.springframework.data.jpa.repository.JpaRepository

interface OrderProposalRepository : JpaRepository<OrderProposal, Long> {
    fun findByUserIdAndId(userId: Long, id: Long): OrderProposal?
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<OrderProposal>
    fun findAllByUserIdAndStockIdOrderByCreatedAtDesc(userId: Long, stockId: Long): List<OrderProposal>
}
