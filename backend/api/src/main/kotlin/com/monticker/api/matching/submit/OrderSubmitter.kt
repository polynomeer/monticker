package com.monticker.api.matching.submit

import java.math.BigDecimal
import java.time.Instant

/** ADR-047 — paper 파사드가 쓰는 시장가 주문 제출. 리스크 게이트(@RiskChecked)를 통과한다. */
interface OrderSubmitter {
    /**
     * @param idempotencyKey ADR-051 — 서버 내부에서 이벤트를 소비해 주문을 낼 때의 중복 방지 키.
     *   같은 키로 다시 부르면 새 주문·새 체결 없이 첫 주문의 결과를 돌려준다. 사용자가 화면에서
     *   직접 낸 주문은 null로 두고 `X-Idempotency-Key` 필터(ADR-007)가 대신 보호한다.
     */
    fun submitMarket(
        userId: Long,
        stockId: Long,
        side: String,
        quantity: Int,
        idempotencyKey: String? = null,
    ): MarketOrderResult
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
