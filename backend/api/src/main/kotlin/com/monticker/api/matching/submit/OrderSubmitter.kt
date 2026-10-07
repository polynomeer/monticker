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
        /** ADR-085 — 진입 출처. 호출하는 서버 경로가 정한다(기본값 없음 — 새 경로가 출처를 빠뜨리지 않게). */
        origin: OrderOrigin,
        idempotencyKey: String? = null,
    ): MarketOrderResult

    /**
     * ADR-074 — 지정가 주문. 가격이 이미 교차하면 즉시 체결(fill 1건), 아니면 미체결(PENDING)로 남고
     * [LimitOrderSweeper][com.monticker.api.matching.application.LimitOrderSweeper]가 이후 시세가 교차할 때 체결한다.
     * 리스크 게이트(@RiskChecked)는 제출 시점에 한 번 — 이후 체결은 이미 승인된 주문의 이행이다.
     */
    fun submitLimit(
        userId: Long,
        stockId: Long,
        side: String,
        quantity: Int,
        limitPrice: BigDecimal,
        /** ADR-085 — 진입 출처. 지정가가 나중에 스위퍼로 체결돼도 주문 행에 남은 이 값이 체결로 이어진다. */
        origin: OrderOrigin,
    ): LimitOrderResult
}

/** 지정가 주문 제출 결과 — 즉시 체결됐으면 [fill]이 있고 [status]는 FILLED, 아니면 PENDING. */
data class LimitOrderResult(
    val orderId: Long,
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val limitPrice: BigDecimal,
    val status: String,
    val fill: MarketOrderResult?,
)

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
