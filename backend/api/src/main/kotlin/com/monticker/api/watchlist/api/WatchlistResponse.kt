package com.monticker.api.watchlist.api

import com.monticker.api.marketdata.domain.PriceRange52w
import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.domain.WatchlistItem
import java.math.BigDecimal
import java.time.LocalDate

data class WatchlistGroupResponse(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    /** 그룹 안 순서대로(sort_order, id) */
    val items: List<WatchlistItemResponse>,
) {
    companion object {
        fun from(group: WatchlistGroup, ranges: Map<Long, PriceRange52w> = emptyMap()) = WatchlistGroupResponse(
            id = group.id,
            name = group.name,
            sortOrder = group.sortOrder,
            items = group.items.mapIndexed { i, item -> WatchlistItemResponse.from(item, i, ranges[item.stock.id]) },
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
    /** 그룹 안 자리(0부터, 빈칸 없음) */
    val sortOrder: Int = 0,
    /** 52주 최고/최저. 일봉이 없으면 null */
    val range52w: Range52wResponse? = null,
) {
    companion object {
        fun from(item: WatchlistItem, index: Int = item.sortOrder, range: PriceRange52w? = null) = WatchlistItemResponse(
            id = item.id,
            stockId = item.stock.id,
            symbol = item.stock.symbol,
            name = item.stock.name,
            memo = item.memo,
            targetPrice = item.targetPrice,
            sortOrder = index,
            range52w = range?.let { Range52wResponse.from(it) },
        )
    }
}

/**
 * 52주 최고/최저. [fullPeriod]가 false면 일봉이 52주를 다 덮지 못한 것 — [firstDate]~[lastDate](KST) 구간의 고저다.
 */
data class Range52wResponse(
    val high: BigDecimal,
    val low: BigDecimal,
    val from: LocalDate,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val tradingDays: Int,
    val fullPeriod: Boolean,
) {
    companion object {
        fun from(r: PriceRange52w) = Range52wResponse(
            high = r.high,
            low = r.low,
            from = r.from,
            firstDate = r.firstDate,
            lastDate = r.lastDate,
            tradingDays = r.tradingDays,
            fullPeriod = r.fullPeriod,
        )
    }
}
