package com.monticker.api.watchlist.infrastructure

import com.monticker.api.watchlist.domain.WatchlistItem
import org.springframework.data.jpa.repository.JpaRepository

interface WatchlistItemRepository : JpaRepository<WatchlistItem, Long> {
    fun existsByGroupIdAndStockId(groupId: Long, stockId: Long): Boolean
}
