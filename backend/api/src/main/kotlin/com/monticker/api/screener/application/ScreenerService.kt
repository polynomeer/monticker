package com.monticker.api.screener.application

import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.ScreenerRepository
import org.springframework.stereotype.Service

@Service
class ScreenerService(private val repo: ScreenerRepository) {

    fun getItems(
        tab: String    = "realtime",
        market: String = "all",
        sort: String   = "amount",
        limit: Int     = 20,
        offset: Int    = 0,
    ): ScreenerResult {
        val effectiveSort = when (tab) {
            "movers"    -> if (sort == "fall") "fall" else "rise"
            else        -> sort
        }
        val items = repo.findItems(market, effectiveSort, limit.coerceIn(1, 50), offset)
        val total = repo.count(market)
        return ScreenerResult(items, total, offset + items.size < total)
    }
}

data class ScreenerResult(
    val items: List<ScreenerItem>,
    val total: Int,
    val hasMore: Boolean,
)
