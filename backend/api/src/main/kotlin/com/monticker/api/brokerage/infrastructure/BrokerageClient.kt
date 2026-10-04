package com.monticker.api.brokerage.infrastructure

import java.math.BigDecimal
import java.time.Instant
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

/**
 * ADR-056 — 주문 제출 결과의 세 분류. 거부(REJECTED)는 "증권사에 접수되지 않았다"가 확실할 때만 쓴다
 * (정상 응답의 거절, 또는 요청이 아예 나가지 않음). 요청이 나갔는데 확정 응답이 없으면 INDETERMINATE —
 * 증권사에서 체결됐을 수 있다. 애매하면 INDETERMINATE로 보낸다(거부로 잘못 읽으면 이중 주문이 난다).
 */
enum class SubmitOutcome { ACCEPTED, REJECTED, INDETERMINATE }

data class BrokerageOrderResult(
    val outcome: SubmitOutcome,
    val pgOrderId: String? = null, // 증권사 주문 번호 — ACCEPTED일 때만 있다
    val rejectReason: String? = null,
    // KIS의 KRX_FWDG_ORD_ORGNO(지점코드)처럼 주문번호만으로는 취소 호출이 불가능한
    // 프로바이더를 위한 추가 참조값. Toss/Mock은 사용하지 않는다(null).
    val brokerOrderRef: String? = null,
) {
    companion object {
        fun accepted(pgOrderId: String, brokerOrderRef: String? = null) =
            BrokerageOrderResult(SubmitOutcome.ACCEPTED, pgOrderId, brokerOrderRef = brokerOrderRef)
        fun rejected(reason: String?) = BrokerageOrderResult(SubmitOutcome.REJECTED, rejectReason = reason)
        fun indeterminate(reason: String?) = BrokerageOrderResult(SubmitOutcome.INDETERMINATE, rejectReason = reason)
    }
}

/**
 * ADR-056 — 증권사 당일 주문 목록의 한 건. 결과 불명 주문을 (종목, 방향, 수량, 주문시각)으로 매칭하는 데 쓴다 —
 * 두 증권사 모두 우리가 만든 식별자로 주문을 조회할 수 없다.
 */
data class BrokerOrderSnapshot(
    val brokerOrderId: String,
    val brokerOrderRef: String?,
    val symbol: String,
    val side: String,              // BUY | SELL
    val quantity: Int,
    val price: BigDecimal?,        // 지정가. 시장가는 null(또는 0)
    val orderedAt: Instant,
    val status: String,            // SUBMITTED | FILLED | PARTIALLY_FILLED | CANCELLED | REJECTED
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
)

data class BrokerageCancelResult(
    val cancelled: Boolean,
    val reason: String? = null,
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

    /**
     * ADR-056 — 예외를 던지지 않고 [SubmitOutcome]으로 알린다. [clientOrderId]는 우리가 만든 식별자로,
     * 멱등 키를 지원하는 증권사(Toss)에는 그대로 보낸다.
     */
    fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest, clientOrderId: String): BrokerageOrderResult

    /** 증권사에 실제로 취소를 요청한다. brokerOrderRef는 submitOrder()가 돌려준 값을 그대로 넘긴다. */
    fun cancelOrder(credentials: BrokerageCredentials, pgOrderId: String, brokerOrderRef: String?): BrokerageCancelResult

    fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus
    fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem>

    /**
     * ADR-056 — [date](KST) 하루의 해당 종목·방향 주문 목록. **조회 실패는 null, 주문 없음은 빈 리스트** —
     * 둘을 섞으면 "조회가 안 됐을 뿐"을 "주문이 안 들어갔다"로 읽어 버린다(ADR-053과 같은 함정).
     */
    fun findOrders(credentials: BrokerageCredentials, date: LocalDate, symbol: String, side: String): List<BrokerOrderSnapshot>?
    fun getBalance(credentials: BrokerageCredentials): BrokerageBalance
}
