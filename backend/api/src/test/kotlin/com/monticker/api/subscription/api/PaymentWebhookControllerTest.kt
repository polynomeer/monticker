package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.subscription.application.SubscribeResult
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.PlanCode
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
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
    private val paymentRecordRepository = mockk<PaymentRecordRepository>(relaxed = true)
    private val subscriptionService = mockk<SubscriptionService>()
    private val jwtTokenProvider = mockk<JwtTokenProvider>()

    private val controller = PaymentWebhookController(
        tossPgClient, paymentRecordRepository, subscriptionService, jwtTokenProvider,
    )

    @Test
    fun `confirm은 요청 바디가 아니라 JWT의 userId로 구독을 활성화한다`() {
        // 토큰의 실제 주인은 userId=42다.
        every { jwtTokenProvider.getUserId("real-token") } returns 42L
        every { tossPgClient.confirmPayment(any(), any(), any()) } returns
            PaymentResult(success = true, pgTransactionId = "toss_tx_1")
        val activatedUserId = slot<Long>()
        every {
            subscriptionService.activateConfirmedSubscription(capture(activatedUserId), PlanCode.PRO, "toss_tx_1")
        } returns SubscribeResult.success(PlanCode.PRO, paymentId = 1L)

        // 요청 바디에 userId 필드 자체가 없다 — 과거엔 여기에 999L 같은 임의 값을 넣을 수 있었다.
        val req = PaymentWebhookController.ConfirmRequest(
            paymentKey = "pk_1", orderId = "order_1", amount = BigDecimal("9900"), planCode = "PRO",
        )

        val response = controller.confirm("Bearer real-token", req)

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(activatedUserId.captured).isEqualTo(42L) // 토큰 주인 42, 바디에 999 같은 값이 있었어도 무시됨
        verify { subscriptionService.activateConfirmedSubscription(42L, PlanCode.PRO, "toss_tx_1") }
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
