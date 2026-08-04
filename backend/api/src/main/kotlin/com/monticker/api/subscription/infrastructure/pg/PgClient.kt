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

interface PgClient {
    fun requestPayment(request: PaymentRequest): PaymentResult
    fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult
}
