package com.monticker.api.analytics.infrastructure

import com.monticker.api.analytics.domain.RegimeHistory
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RegimeHistoryRepository : JpaRepository<RegimeHistory, Long> {
    fun findByStockIdAndRegimeDate(stockId: Long, regimeDate: LocalDate): RegimeHistory?
    fun findByMarketAndRegimeDate(market: String, regimeDate: LocalDate): RegimeHistory?
}
