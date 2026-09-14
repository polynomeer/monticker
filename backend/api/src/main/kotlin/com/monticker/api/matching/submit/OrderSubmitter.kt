package com.monticker.api.matching.submit

import java.math.BigDecimal
import java.time.Instant

/** ADR-047 — paper 파사드가 쓰는 시장가 주문 제출. 리스크 게이트(@RiskChecked)를 통과한다. */
interface OrderSubmitter {
    fun submitMarket(userId: Long, stockId: Long, side: String, quantity: Int): MarketOrderResult
}

/** 체결 결과 — MARKET 주문은 즉시 체결되거나(fill 1건) 거절된다. */
data class MarketOrderResult(
    val orderId: Long,
    val fillId: Long,
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val fillPrice: BigDecimal,
    val amount: BigDecimal,
    val filledAt: Instant,
)
