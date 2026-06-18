package com.monticker.api.watchlist.application

import com.monticker.api.stock.infrastructure.StockRepository
import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.domain.WatchlistItem
import com.monticker.api.watchlist.infrastructure.WatchlistGroupRepository
import com.monticker.api.watchlist.infrastructure.WatchlistItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class WatchlistService(
    private val groupRepository: WatchlistGroupRepository,
    private val itemRepository: WatchlistItemRepository,
    private val stockRepository: StockRepository,
) {
    @Transactional(readOnly = true)
    fun getGroups(userId: Long): List<WatchlistGroup> =
        groupRepository.findAllByUserIdOrderBySortOrder(userId)

    fun createGroup(userId: Long, name: String): WatchlistGroup {
        require(name.isNotBlank()) { "Group name must not be blank" }
        val group = WatchlistGroup(userId = userId, name = name)
        return groupRepository.save(group)
    }

    fun addItem(userId: Long, groupId: Long, stockId: Long, memo: String?): WatchlistItem {
        val group = groupRepository.findById(groupId).orElseThrow {
            NoSuchElementException("Watchlist group not found: $groupId")
        }
        require(group.userId == userId) { "Access denied" }

        val stock = stockRepository.findById(stockId).orElseThrow {
            NoSuchElementException("Stock not found: $stockId")
        }

        check(!itemRepository.existsByGroupIdAndStockId(groupId, stockId)) {
            "Stock already in watchlist"
        }

        val item = WatchlistItem(group = group, stock = stock, memo = memo)
        return itemRepository.save(item)
    }

    fun removeItem(userId: Long, itemId: Long) {
        val item = itemRepository.findById(itemId).orElseThrow {
            NoSuchElementException("Watchlist item not found: $itemId")
        }
        require(item.group.userId == userId) { "Access denied" }
        itemRepository.delete(item)
    }
}
