package com.monticker.api.analytics.infrastructure

import com.monticker.api.analytics.domain.TaxHarvestingLog
import org.springframework.data.jpa.repository.JpaRepository

interface TaxHarvestingLogRepository : JpaRepository<TaxHarvestingLog, Long> {
    fun findAllByUserIdOrderBySimulatedAtDesc(userId: Long): List<TaxHarvestingLog>
}
