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
    /** ADR-085 진입 출처. 판정할 수 없던 과거 거래는 null. */
    val origin: String? = null,
    val originRef: Long? = null,
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

    /** 사용자 소유 거래만 한 번에(리플레이·원장 출처 표시의 N+1 제거). 남의 id·없는 id는 결과에 없다. */
    fun findOwnedByIds(userId: Long, ids: Collection<Long>): List<PaperTradeSummary> {
        if (ids.isEmpty()) return emptyList()
        return tradeRepo.findAllById(ids.distinct()).filter { it.userId == userId }.map { it.toSummary() }
    }

    private fun com.monticker.api.paper.domain.PaperTrade.toSummary() = PaperTradeSummary(
        id = id,
        userId = userId,
        stockId = stockId,
        side = side,
        quantity = quantity,
        price = price,
        amount = amount,
        tradedAt = tradedAt,
        origin = origin,
        originRef = originRef,
    )
}
