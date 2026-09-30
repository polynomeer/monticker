package com.monticker.api.subscription.infrastructure.pg

import java.math.BigDecimal

data class PaymentRequest(
    val userId: Long,
    val planCode: String,
    val amount: BigDecimal,
    val currency: String = "KRW",
)

/**
 * 결제 실패의 성격. "카드가 거절됐다"와 "PG가 응답하지 않는다"는 전혀 다른 사건인데
 * 예전에는 둘 다 `success=false` 하나로 뭉뚱그려져 있었다 — 그래서 PG 장애가 나면
 * 갱신 배치가 그걸 결제 거절로 읽고 3회 실패 다운그레이드 로직을 태워,
 * **돈 내는 고객을 PG 장애 때문에 FREE로 내리는** 경로가 열려 있었다 (ADR-053).
 */
enum class PaymentFailureKind {
    /** PG가 정상 응답했고 결제가 거절됐다. 재시도해도 같은 결과 — 사용자 조치가 필요하다. */
    DECLINED,

    /** 요청이 PG에 닿지 못했다(서킷브레이커 OPEN, connect 실패). 청구되지 않았음이 확실하다. */
    UNAVAILABLE,

    /** 요청은 보냈는데 응답을 못 받았다(read 타임아웃, 5xx). **청구됐는지 알 수 없다.** */
    INDETERMINATE,
}

data class PaymentResult(
    val success: Boolean,
    val pgTransactionId: String? = null,
    val failureReason: String? = null,
    /** 실패했을 때만 의미가 있다. null이면 분류되지 않은 구식 실패 = DECLINED로 취급한다. */
    val failureKind: PaymentFailureKind? = null,
)

data class RefundResult(
    val success: Boolean,
    val failureReason: String? = null,
)

data class PaymentStatusResult(
    val found: Boolean,
    val status: String? = null,     // DONE | CANCELED | PARTIAL_CANCELED | EXPIRED | ...
    val totalAmount: BigDecimal? = null,
    val paymentKey: String? = null,
    /** 조회 자체가 실패했다(PG 장애). found=false 지만 "결제가 없다"는 뜻은 아니다. */
    val lookupFailed: Boolean = false,
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

    /**
     * orderId로 결제를 되짚는다(토스: GET /v1/payments/orders/{orderId}).
     *
     * 타임아웃처럼 "청구됐는지 알 수 없는" 상태에서 유일하게 진실을 알아내는 방법이다.
     * paymentKey는 PG가 만들어 응답에 실어주므로 응답을 못 받았으면 우리에겐 없다 —
     * 그래서 우리가 만든 orderId로 물어야 한다. 이것이 orderId를 재시도 간에
     * 결정적으로(deterministic) 만들어야 하는 이유다 (ADR-053).
     */
    fun findPaymentByOrderId(orderId: String): PaymentStatusResult
}
