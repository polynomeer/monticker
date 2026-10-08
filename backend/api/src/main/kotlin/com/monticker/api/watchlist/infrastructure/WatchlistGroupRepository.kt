package com.monticker.api.watchlist.infrastructure

import com.monticker.api.watchlist.domain.WatchlistGroup
import org.springframework.data.jpa.repository.JpaRepository

interface WatchlistGroupRepository : JpaRepository<WatchlistGroup, Long> {
    fun findAllByUserIdOrderBySortOrderAscIdAsc(userId: Long): List<WatchlistGroup>
}
