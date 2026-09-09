package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.RebalanceExecution
import com.monticker.api.brokerage.domain.RebalanceExecutionLeg
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface RebalanceExecutionRepository : JpaRepository<RebalanceExecution, Long> {
    fun findAllByUserIdOrderByRequestedAtDesc(userId: Long, pageable: Pageable): Page<RebalanceExecution>
}

interface RebalanceExecutionLegRepository : JpaRepository<RebalanceExecutionLeg, Long> {
    fun findAllByExecutionId(executionId: Long): List<RebalanceExecutionLeg>
}
