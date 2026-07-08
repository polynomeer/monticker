package com.monticker.api.analytics.infrastructure

import com.monticker.api.analytics.domain.DetectedPattern
import org.springframework.data.jpa.repository.JpaRepository

interface DetectedPatternRepository : JpaRepository<DetectedPattern, Long> {
    fun findAllByStockIdOrderByDetectedAtDesc(stockId: Long): List<DetectedPattern>
}
