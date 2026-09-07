package com.monticker.api.brokerage.infrastructure

import java.math.BigDecimal
import java.time.LocalDate

// ── 요청/응답 DTO (KIS Open API 구조 기반) ─────────────────────────────────────

data class BrokerageToken(
    val accessToken: String,
    val expiresIn: Long,           // seconds
)

/**
 * ADR-025 — issueToken() 이후의 모든 인증 호출에 실어야 하는 값 전부를 묶는다.
 * KIS는 authorization/tr_id뿐 아니라 매 요청마다 appkey/appsecret 헤더와 계좌번호(CANO/
 * ACNT_PRDT_CD)를 요구하므로, 발급받은 토큰만으로는 실제 호출이 불가능하다.
 */
data class BrokerageCredentials(
    val token: BrokerageToken,
    val appKey: String,
    val appSecret: String,
    val accountNumber: String,
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
    fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest): BrokerageOrderResult
    fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus
    fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem>
    fun getBalance(credentials: BrokerageCredentials): BrokerageBalance
}
