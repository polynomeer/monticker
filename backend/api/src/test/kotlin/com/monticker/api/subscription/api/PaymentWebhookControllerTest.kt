package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.subscription.application.SubscribeResult
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.PaymentRecord
import com.monticker.api.subscription.domain.PaymentStatus
import com.monticker.api.subscription.domain.PlanCode
import com.monticker.api.subscription.domain.SubscriptionPlan
import com.monticker.api.subscription.infrastructure.pg.PaymentFailureKind
import com.monticker.api.subscription.infrastructure.pg.PaymentResult
import com.monticker.api.subscription.infrastructure.pg.PaymentStatusResult
import com.monticker.api.subscription.infrastructure.pg.TossPgClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 실제로 있던 취약점: confirm()이 인증된 사용자가 아니라 요청 바디의 userId를 그대로 믿었다
 * (broken object-level authorization) — 로그인한 사용자가 임의의 userId를 넣어 남의 계정에
 * 구독을 활성화시킬 수 있었다. 지금은 userId를 Authorization 헤더의 JWT에서만 뽑는다.
 */
class PaymentWebhookControllerTest {

    private val tossPgClient = mockk<TossPgClient>()
    private val subscriptionService = mockk<SubscriptionService>()
    private val jwtTokenProvider = mockk<JwtTokenProvider>()

    private val controller = PaymentWebhookController(
        tossPgClient, subscriptionService, jwtTokenProvider,
    )

    private fun plan(price: String = "9900") =
        SubscriptionPlan(id = 2L, code = PlanCode.PRO, name = "Pro", price = BigDecimal(price))

    /** preparePayment 가 만들어 둔 PENDING 기록. confirm 은 이걸 orderId 로 찾아 확정한다. */
    private fun prepared(
        orderId: String = "sub_42_abc",
        status: PaymentStatus = PaymentStatus.PENDING,
        amount: String = "9900",
        txId: String? = null,
    ) = PaymentRecord(
        id = 7L, userId = 42L, plan = plan(amount), amount = BigDecimal(amount),
        pgOrderId = orderId, status = status, pgTransactionId = txId,
    )

    @Test
    fun `confirm은 요청 바디가 아니라 JWT의 userId로 구독을 활성화한다`() {
        // 토큰의 실제 주인은 userId=42다.
        every { jwtTokenProvider.getUserId("real-token") } returns 42L
        every { subscriptionService.findPreparedPayment(42L, "sub_42_abc") } returns prepared()
        every { tossPgClient.confirmPayment(any(), any(), any()) } returns
            PaymentResult(success = true, pgTransactionId = "toss_tx_1")
        val activatedUserId = slot<Long>()
        every {
            subscriptionService.activateConfirmedSubscription(capture(activatedUserId), "sub_42_abc", "toss_tx_1")
        } returns SubscribeResult.success(PlanCode.PRO, paymentId = 7L)

        // 요청 바디에 userId 필드 자체가 없다 — 과거엔 여기에 999L 같은 임의 값을 넣을 수 있었다.
        val response = controller.confirm("Bearer real-token",
            PaymentWebhookController.ConfirmRequest(paymentKey = "pk_1", orderId = "sub_42_abc"))

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(activatedUserId.captured).isEqualTo(42L) // 토큰 주인 42, 바디에 999 같은 값이 있었어도 무시됨
        verify { subscriptionService.activateConfirmedSubscription(42L, "sub_42_abc", "toss_tx_1") }
    }

    // ── ADR-059: 금액과 orderId 를 서버가 쥔다 ────────────────────────────────

    @Test
    fun `confirm은 클라이언트 금액을 받지 않고 준비 시점에 서버가 정한 금액을 PG에 보낸다`() {
        // 예전에는 amount 가 요청 바디에 있었다. 토스 confirm 은 "보낸 금액이 실제 결제 금액과
        // 같은가"만 검증하므로, 100원을 결제하고 amount=100, planCode=PRO 로 confirm 하면
        // 토스는 DONE 을 주고 우리는 PRO 를 활성화했다 — 결제 우회였다.
        every { jwtTokenProvider.getUserId("t") } returns 42L
        every { subscriptionService.findPreparedPayment(42L, "sub_42_abc") } returns prepared(amount = "9900")
        val sentAmount = slot<BigDecimal>()
        every { tossPgClient.confirmPayment("pk_1", "sub_42_abc", capture(sentAmount)) } returns
            PaymentResult(success = true, pgTransactionId = "tx")
        every { subscriptionService.activateConfirmedSubscription(42L, "sub_42_abc", "tx") } returns
            SubscribeResult.success(PlanCode.PRO, paymentId = 7L)

        controller.confirm("Bearer t",
            PaymentWebhookController.ConfirmRequest(paymentKey = "pk_1", orderId = "sub_42_abc"))

        assertThat(sentAmount.captured).isEqualByComparingTo(BigDecimal("9900"))
    }

    @Test
    fun `준비되지 않은 orderId는 PG를 찌르지도 않고 거절한다`() {
        every { jwtTokenProvider.getUserId("t") } returns 42L
        every { subscriptionService.findPreparedPayment(42L, "남의-주문") } returns null

        val response = controller.confirm("Bearer t",
            PaymentWebhookController.ConfirmRequest(paymentKey = "pk_1", orderId = "남의-주문"))

        assertThat(response.statusCode.is4xxClientError).isTrue()
        verify(exactly = 0) { tossPgClient.confirmPayment(any(), any(), any()) }
    }

    @Test
    fun `이미 확정된 주문의 confirm 재수신은 PG를 다시 찌르지 않고 성공을 돌려준다`() {
        // 네트워크 재시도로 confirm 이 두 번 도착하는 경우. 토스는 2회차를 4xx 로 거절하는데,
        // 예전에는 그걸 "결제 실패"로 읽고 400 을 돌려줬다 — 실제로는 성공한 결제인데도.
        every { jwtTokenProvider.getUserId("t") } returns 42L
        every { subscriptionService.findPreparedPayment(42L, "sub_42_abc") } returns
            prepared(status = PaymentStatus.SUCCESS, txId = "tx_first")

        val response = controller.confirm("Bearer t",
            PaymentWebhookController.ConfirmRequest(paymentKey = "pk_1", orderId = "sub_42_abc"))

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body!!.pgTransactionId).isEqualTo("tx_first")
        verify(exactly = 0) { tossPgClient.confirmPayment(any(), any(), any()) }
        verify(exactly = 0) { subscriptionService.activateConfirmedSubscription(any(), any(), any()) }
    }

    @Test
    fun `confirm이 실패해도 PG가 이미 승인했다면 구독을 활성화한다`() {
        // "돈은 빠졌는데 구독은 없다"를 막는 자리다.
        every { jwtTokenProvider.getUserId("t") } returns 42L
        every { subscriptionService.findPreparedPayment(42L, "sub_42_abc") } returns prepared()
        every { tossPgClient.confirmPayment(any(), any(), any()) } returns
            PaymentResult(success = false, failureReason = "이미 처리된 결제입니다",
                failureKind = PaymentFailureKind.DECLINED)
        every { tossPgClient.getPaymentStatus("pk_1") } returns
            PaymentStatusResult(found = true, status = "DONE", totalAmount = BigDecimal("9900"), paymentKey = "pk_1")
        every { subscriptionService.activateConfirmedSubscription(42L, "sub_42_abc", "pk_1") } returns
            SubscribeResult.success(PlanCode.PRO, paymentId = 7L)

        val response = controller.confirm("Bearer t",
            PaymentWebhookController.ConfirmRequest(paymentKey = "pk_1", orderId = "sub_42_abc"))

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        verify { subscriptionService.activateConfirmedSubscription(42L, "sub_42_abc", "pk_1") }
    }

    @Test
    fun `금액이 어긋나면 PG가 DONE이라 해도 활성화하지 않는다`() {
        every { jwtTokenProvider.getUserId("t") } returns 42L
        every { subscriptionService.findPreparedPayment(42L, "sub_42_abc") } returns prepared(amount = "29900")
        every { tossPgClient.confirmPayment(any(), any(), any()) } returns
            PaymentResult(success = false, failureKind = PaymentFailureKind.DECLINED)
        every { tossPgClient.getPaymentStatus("pk_1") } returns
            PaymentStatusResult(found = true, status = "DONE", totalAmount = BigDecimal("9900"))   // 100원짜리

        val response = controller.confirm("Bearer t",
            PaymentWebhookController.ConfirmRequest(paymentKey = "pk_1", orderId = "sub_42_abc"))

        assertThat(response.statusCode.is4xxClientError).isTrue()
        verify(exactly = 0) { subscriptionService.activateConfirmedSubscription(any(), any(), any()) }
    }

    @Test
    fun `webhook은 paymentKey로 PG에 재조회해 검증한 뒤에만 처리하고, 바디 내용을 그대로 신뢰하지 않는다`() {
        every { tossPgClient.getPaymentStatus("pk_1") } returns
            PaymentStatusResult(found = true, status = "DONE", totalAmount = BigDecimal("9900"))

        val payload = mapOf(
            "eventType" to "PAYMENT_STATUS_CHANGED",
            "data" to mapOf("paymentKey" to "pk_1", "status" to "이것은-위조됐을-수도-있는-값"),
        )

        val response = controller.webhook(payload)

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        verify { tossPgClient.getPaymentStatus("pk_1") } // 재조회를 실제로 했는지가 핵심 검증 포인트
    }
}
