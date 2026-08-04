package com.monticker.api.settlement.creator.infrastructure

import com.monticker.api.settlement.creator.domain.CreatorPayout
import com.monticker.api.settlement.creator.domain.PayoutStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CreatorPayoutRepository : JpaRepository<CreatorPayout, Long> {
    fun findAllByCreatorIdOrderByRequestedAtDesc(creatorId: Long, pageable: Pageable): Page<CreatorPayout>
    fun findAllByStatus(status: PayoutStatus): List<CreatorPayout>
}
