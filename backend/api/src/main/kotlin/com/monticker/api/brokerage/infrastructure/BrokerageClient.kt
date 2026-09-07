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
    // ADR-026 — Toss의 accountSeq처럼 계좌번호만으로 호출 불가능한 프로바이더를 위한
    // 추가 참조값. connect() 시점에 resolveAccountRef()로 한 번만 조회해 저장해둔다.
    val providerAccountRef: String? = null,
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

    /**
     * ADR-026 — 계좌번호 문자열만으로 API 호출이 불가능한 프로바이더(Toss의 accountSeq)를
     * 위한 추가 계좌 참조 조회. KIS처럼 계좌번호를 그대로 쪼개 쓰는 프로바이더는 오버라이드하지
     * 않는다(기본값 null).
     */
    fun resolveAccountRef(token: BrokerageToken, accountNumber: String): String? = null

    fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest): BrokerageOrderResult
    fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus
    fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem>
    fun getBalance(credentials: BrokerageCredentials): BrokerageBalance
}
