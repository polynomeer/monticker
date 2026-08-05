package com.monticker.api.subscription.api

import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.PlanCode
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.pg.TossPgClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * 프론트엔드 → 백엔드 결제 확정 엔드포인트.
 *
 * 토스페이먼츠 SDK 결제 완료 후 프론트가 아래 파라미터를 POST한다:
 *   paymentKey, orderId, amount, planCode, userId
 *
 * 백엔드에서 토스 API /v1/payments/confirm을 호출해 최종 승인 처리.
 * mock.enabled=false인 경우에만 활성화.
 */
@RestController
@RequestMapping("/api/subscription/payment")
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "false")
class PaymentWebhookController(
    private val tossPgClient: TossPgClient,
    private val paymentRecordRepository: PaymentRecordRepository,
    private val subscriptionService: SubscriptionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ConfirmRequest(
        val paymentKey: String,
        val orderId: String,
        val amount: BigDecimal,
        val planCode: String,
        val userId: Long,
    )

    data class ConfirmResponse(
        val success: Boolean,
        val pgTransactionId: String?,
        val message: String?,
    )

    /**
     * 프론트엔드 SDK 결제 완료 후 호출.
     * 토스 측 결제 확정(confirm)을 수행하고 구독을 활성화한다.
     */
    @PostMapping("/confirm")
    fun confirm(@RequestBody req: ConfirmRequest): ResponseEntity<ConfirmResponse> {
        log.info("결제 확정 요청: userId={} plan={} orderId={}", req.userId, req.planCode, req.orderId)

        val result = tossPgClient.confirmPayment(
            paymentKey = req.paymentKey,
            orderId    = req.orderId,
            amount     = req.amount,
        )

        if (!result.success) {
            log.warn("결제 확정 실패: userId={} reason={}", req.userId, result.failureReason)
            return ResponseEntity.badRequest().body(
                ConfirmResponse(success = false, pgTransactionId = null, message = result.failureReason)
            )
        }

        // 결제 성공 → 구독 활성화
        val planCode = runCatching { PlanCode.valueOf(req.planCode) }.getOrElse {
            return ResponseEntity.badRequest().body(
                ConfirmResponse(success = false, pgTransactionId = null, message = "유효하지 않은 플랜: ${req.planCode}")
            )
        }
        subscriptionService.subscribe(userId = req.userId, planCode = planCode)

        return ResponseEntity.ok(
            ConfirmResponse(success = true, pgTransactionId = result.pgTransactionId, message = null)
        )
    }

    /**
     * 토스페이먼츠 → 서버 웹훅 수신 (비동기 이벤트).
     * 주로 가상계좌 입금, 자동취소 등 비동기 상태 변경 시 호출된다.
     * 실운영에서는 서명 검증 필요 (Toss-Signature 헤더).
     */
    @PostMapping("/webhook")
    fun webhook(@RequestBody payload: Map<String, Any>): ResponseEntity<Void> {
        val eventType  = payload["eventType"] as? String ?: return ResponseEntity.ok().build()
        val paymentKey = (payload["data"] as? Map<*, *>)?.get("paymentKey") as? String

        log.info("토스 웹훅 수신: eventType={} paymentKey={}", eventType, paymentKey)
        // 필요 시 eventType별 처리 추가 (예: PAYMENT_STATUS_CHANGED, DEPOSIT_CALLBACK 등)

        return ResponseEntity.ok().build()
    }
}
