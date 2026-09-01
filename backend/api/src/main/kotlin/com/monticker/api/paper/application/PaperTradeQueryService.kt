package com.monticker.api.paper.application

import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

data class PaperTradeSummary(
    val id: Long,
    val userId: Long,
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val price: BigDecimal,
    val amount: BigDecimal,
    val tradedAt: Instant = Instant.now(),
)

/**
 * paper_trades에 대한 읽기 전용 조회 — wallet 모듈(EmotionTagService, ReceiptService)이
 * paper.infrastructure.PaperTradeRepository를 직접 참조하지 않도록 감싼다.
 */
@Service
@Transactional(readOnly = true)
class PaperTradeQueryService(
    private val tradeRepo: PaperTradeRepository,
) {
    fun getById(id: Long): PaperTradeSummary =
        tradeRepo.findById(id).orElseThrow { NoSuchElementException("Paper trade not found: $id") }.toSummary()

    fun findById(id: Long): PaperTradeSummary? =
        tradeRepo.findById(id).map { it.toSummary() }.orElse(null)

    private fun com.monticker.api.paper.domain.PaperTrade.toSummary() = PaperTradeSummary(
        id = id,
        userId = userId,
        stockId = stockId,
        side = side,
        quantity = quantity,
        price = price,
        amount = amount,
        tradedAt = tradedAt,
    )
}
