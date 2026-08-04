package com.monticker.api.brokerage.infrastructure

import java.math.BigDecimal
import java.time.LocalDate

// ── 요청/응답 DTO (KIS Open API 구조 기반) ─────────────────────────────────────

data class BrokerageToken(
    val accessToken: String,
    val expiresIn: Long,           // seconds
)

data class BrokerageOrderRequest(
    val symbol: String,
    val side: String,              // BUY | SELL
    val orderType: String,         // MARKET | LIMIT
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
)

data class BrokerageOrderResult(
    val pgOrderId: String,         // 증권사 주문 번호
    val status: String,            // SUBMITTED | REJECTED
    val rejectReason: String? = null,
)

data class BrokerageOrderStatus(
    val pgOrderId: String,
    val status: String,            // SUBMITTED | FILLED | PARTIALLY_FILLED | CANCELLED | REJECTED
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
)

data class BrokerageSettlementItem(
    val pgOrderId: String,
    val symbol: String,
    val side: String,
    val quantity: Int,
    val fillPrice: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val settleDate: LocalDate,
)

data class BrokerageBalance(
    val cash: BigDecimal,
    val totalEvaluated: BigDecimal,
    val holdings: List<BrokerageHolding>,
)

data class BrokerageHolding(
    val symbol: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val currentPrice: BigDecimal,
)

// ── 인터페이스 ─────────────────────────────────────────────────────────────────

interface BrokerageClient {
    fun issueToken(appKey: String, appSecret: String): BrokerageToken
    fun submitOrder(token: BrokerageToken, request: BrokerageOrderRequest): BrokerageOrderResult
    fun getOrderStatus(token: BrokerageToken, pgOrderId: String): BrokerageOrderStatus
    fun getSettlements(token: BrokerageToken, date: LocalDate): List<BrokerageSettlementItem>
    fun getBalance(token: BrokerageToken): BrokerageBalance
}
