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

data class BillingKeyResult(
    val success: Boolean,
    val billingKey: String? = null,
    val cardCompany: String? = null,
    val cardLast4: String? = null,
    val failureReason: String? = null,
)

interface PgClient {
    fun requestPayment(request: PaymentRequest): PaymentResult
    fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult

    /**
     * 정기결제 카드 등록. 프론트가 토스 SDK의 requestBillingAuth() 위젯으로 카드 인증을 마치면
     * successUrl로 {authKey, customerKey}가 리다이렉트되고, 그 값을 이 메서드로 넘겨 실제
     * billingKey를 발급받는다(토스: POST /v1/billing/authorizations/issue). authKey는
     * 1회용이라 재사용 불가 — 발급받은 billingKey를 저장해야 한다.
     */
    fun issueBillingKey(authKey: String, customerKey: String): BillingKeyResult

    /**
     * 저장된 billingKey로 자동결제를 실행한다(토스: POST /v1/billing/{billingKey}).
     * 정기결제 갱신 배치(SubscriptionService.renewSubscription)에서 호출한다.
     */
    fun chargeBilling(
        billingKey: String,
        customerKey: String,
        amount: BigDecimal,
        orderId: String,
        orderName: String,
    ): PaymentResult

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
