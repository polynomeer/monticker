package com.monticker.api.subscription.infrastructure.pg

import java.math.BigDecimal

data class PaymentRequest(
    val userId: Long,
    val planCode: String,
    val amount: BigDecimal,
    val currency: String = "KRW",
)

data class PaymentResult(
    val success: Boolean,
    val pgTransactionId: String? = null,
    val failureReason: String? = null,
)

data class RefundResult(
    val success: Boolean,
    val failureReason: String? = null,
)

data class PaymentStatusResult(
    val found: Boolean,
    val status: String? = null,     // DONE | CANCELED | PARTIAL_CANCELED | EXPIRED | ...
    val totalAmount: BigDecimal? = null,
)

interface PgClient {
    fun requestPayment(request: PaymentRequest): PaymentResult
    fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult

    /**
     * PG 서버에 직접 물어서 얻는 "권위 있는" 결제 상태. 토스페이먼츠의 일반 결제 상태 웹훅
     * (PAYMENT_STATUS_CHANGED 등)에는 서명이 없다 — payout.changed/seller.changed 웹훅에만
     * tosspayments-webhook-signature가 붙는다(토스 개발자센터 문서 확인, 2026-09).
     * 그래서 웹훅 바디 내용을 그대로 믿지 않고, 웹훅을 "트리거"로만 쓰고 이 메서드로 PG에
     * 직접 재조회한 값을 신뢰해야 한다 — 웹훅은 위조 가능하지만 이 API 호출은 우리 시크릿
     * 키로 인증되므로 위조할 수 없다.
     */
    fun getPaymentStatus(paymentKey: String): PaymentStatusResult
}
