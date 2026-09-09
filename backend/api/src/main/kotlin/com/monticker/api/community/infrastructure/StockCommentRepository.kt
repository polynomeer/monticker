package com.monticker.api.community.infrastructure

import com.monticker.api.community.domain.StockComment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface StockCommentRepository : JpaRepository<StockComment, Long> {
    fun findByStockIdAndDeletedAtIsNullOrderByCreatedAtDesc(stockId: Long, pageable: Pageable): List<StockComment>
    fun findByStockIdAndEventIdAndDeletedAtIsNullOrderByCreatedAtDesc(stockId: Long, eventId: Long, pageable: Pageable): List<StockComment>
}
