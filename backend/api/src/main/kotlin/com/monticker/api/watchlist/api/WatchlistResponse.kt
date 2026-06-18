package com.monticker.api.watchlist.api

import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.domain.WatchlistItem
import java.math.BigDecimal

data class WatchlistGroupResponse(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val items: List<WatchlistItemResponse>,
) {
    companion object {
        fun from(group: WatchlistGroup) = WatchlistGroupResponse(
            id = group.id,
            name = group.name,
            sortOrder = group.sortOrder,
            items = group.items.map { WatchlistItemResponse.from(it) },
        )
    }
}

data class WatchlistItemResponse(
    val id: Long,
    val stockId: Long,
    val symbol: String,
    val name: String,
    val memo: String?,
    val targetPrice: BigDecimal?,
) {
    companion object {
        fun from(item: WatchlistItem) = WatchlistItemResponse(
            id = item.id,
            stockId = item.stock.id,
            symbol = item.stock.symbol,
            name = item.stock.name,
            memo = item.memo,
            targetPrice = item.targetPrice,
        )
    }
}
