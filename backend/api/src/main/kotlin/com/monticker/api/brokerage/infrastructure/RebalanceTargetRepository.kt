package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.RebalanceTarget
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface RebalanceTargetRepository : JpaRepository<RebalanceTarget, Long> {
    fun findByAccountId(accountId: Long): Optional<RebalanceTarget>
}
