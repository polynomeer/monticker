package com.monticker.api.subscription.infrastructure.pg

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Primary
@Component
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "true", matchIfMissing = true)
class MockPgClient : PgClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun requestPayment(request: PaymentRequest): PaymentResult {
        val txId = "mock_${UUID.randomUUID()}"
        log.info("[MockPG] 결제 성공: user={} plan={} amount={} txId={}", request.userId, request.planCode, request.amount, txId)
        return PaymentResult(success = true, pgTransactionId = txId)
    }

    override fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult {
        log.info("[MockPG] 환불 성공: txId={} amount={}", pgTransactionId, amount)
        return RefundResult(success = true)
    }

    override fun getPaymentStatus(paymentKey: String): PaymentStatusResult =
        PaymentStatusResult(found = true, status = "DONE", totalAmount = BigDecimal.ZERO)

    override fun issueBillingKey(authKey: String, customerKey: String): BillingKeyResult {
        val fakeBillingKey = "mock_billing_${UUID.randomUUID()}"
        log.info("[MockPG] 빌링키 발급 성공: customerKey={} billingKey={}", customerKey, fakeBillingKey)
        return BillingKeyResult(success = true, billingKey = fakeBillingKey, cardCompany = "MockCard", cardLast4 = "1234")
    }

    override fun chargeBilling(
        billingKey: String,
        customerKey: String,
        amount: BigDecimal,
        orderId: String,
        orderName: String,
    ): PaymentResult {
        val txId = "mock_${UUID.randomUUID()}"
        log.info("[MockPG] 정기결제 성공: orderId={} amount={} txId={}", orderId, amount, txId)
        return PaymentResult(success = true, pgTransactionId = txId)
    }
}
