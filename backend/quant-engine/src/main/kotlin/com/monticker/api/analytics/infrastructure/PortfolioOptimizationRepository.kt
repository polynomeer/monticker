package com.monticker.api.analytics.infrastructure

import com.monticker.api.analytics.domain.PortfolioOptimization
import org.springframework.data.jpa.repository.JpaRepository

interface PortfolioOptimizationRepository : JpaRepository<PortfolioOptimization, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<PortfolioOptimization>
}
