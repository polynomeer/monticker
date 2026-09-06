package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.PlanCode
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.pg.TossPgClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * 프론트엔드 → 백엔드 결제 확정 엔드포인트.
 *
 * 토스페이먼츠 SDK 결제 완료 후 프론트가 아래 파라미터를 POST한다:
 *   paymentKey, orderId, amount, planCode
 *
 * userId는 요청 바디가 아니라 Authorization 헤더의 JWT에서만 뽑는다 — 예전에는 바디의
 * userId를 그대로 믿었는데, 이러면 로그인한 사용자가 임의의 userId를 넣어 남의 계정에
 * 구독을 활성화시킬 수 있었다(broken object-level authorization). SubscriptionController의
 * 다른 엔드포인트들과 동일한 패턴으로 통일.
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
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ConfirmRequest(
        val paymentKey: String,
        val orderId: String,
        val amount: BigDecimal,
        val planCode: String,
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
    fun confirm(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: ConfirmRequest,
    ): ResponseEntity<ConfirmResponse> {
        val userId = jwtTokenProvider.getUserId(token.removePrefix("Bearer "))
        log.info("결제 확정 요청: userId={} plan={} orderId={}", userId, req.planCode, req.orderId)

        val result = tossPgClient.confirmPayment(
            paymentKey = req.paymentKey,
            orderId    = req.orderId,
            amount     = req.amount,
        )

        if (!result.success) {
            log.warn("결제 확정 실패: userId={} reason={}", userId, result.failureReason)
            return ResponseEntity.badRequest().body(
                ConfirmResponse(success = false, pgTransactionId = null, message = result.failureReason)
            )
        }

        val planCode = runCatching { PlanCode.valueOf(req.planCode) }.getOrElse {
            return ResponseEntity.badRequest().body(
                ConfirmResponse(success = false, pgTransactionId = null, message = "유효하지 않은 플랜: ${req.planCode}")
            )
        }

        // subscribe()가 아니라 activateConfirmedSubscription()을 쓴다 — 결제는 위에서 이미
        // 확정됐으므로 pgClient.requestPayment()를 다시 태우면 안 된다(TossPgClient는 그 경로를
        // 스텁으로 항상 실패 처리하도록 만들어져 있어, 그러면 실제로 결제된 고객의 구독이
        // 활성화되지 않는다 — 과거에 실제로 있던 버그).
        val subscribeResult = subscriptionService.activateConfirmedSubscription(
            userId = userId, planCode = planCode, pgTransactionId = result.pgTransactionId!!,
        )

        return if (subscribeResult.success) {
            ResponseEntity.ok(ConfirmResponse(success = true, pgTransactionId = result.pgTransactionId, message = null))
        } else {
            log.error("결제는 확정됐으나 구독 활성화 실패: userId={} plan={} reason={}", userId, planCode, subscribeResult.errorMessage)
            ResponseEntity.internalServerError().body(
                ConfirmResponse(success = false, pgTransactionId = result.pgTransactionId, message = subscribeResult.errorMessage)
            )
        }
    }

    /**
     * 토스페이먼츠 → 서버 웹훅 수신 (비동기 이벤트, 주로 가상계좌 입금/자동취소).
     *
     * 이 웹훅의 바디를 그대로 신뢰하지 않는다 — 토스 개발자센터 문서 기준으로
     * payout.changed/seller.changed 웹훅에만 서명(tosspayments-webhook-signature)이 붙고,
     * 여기서 다루는 일반 결제 상태 웹훅에는 서명이 없다. 그래서 웹훅은 "무슨 일이 있었다"는
     * 트리거로만 쓰고, paymentKey로 PG에 직접 재조회한 값(우리 시크릿 키로 인증되어 위조
     * 불가능)을 권위 있는 상태로 취급한다.
     */
    @PostMapping("/webhook")
    fun webhook(@RequestBody payload: Map<String, Any>): ResponseEntity<Void> {
        val eventType  = payload["eventType"] as? String ?: return ResponseEntity.ok().build()
        val paymentKey = (payload["data"] as? Map<*, *>)?.get("paymentKey") as? String

        if (paymentKey == null) {
            log.info("토스 웹훅 수신 (paymentKey 없음): eventType={}", eventType)
            return ResponseEntity.ok().build()
        }

        val verified = tossPgClient.getPaymentStatus(paymentKey)
        if (!verified.found) {
            log.warn("토스 웹훅 수신했으나 PG 재조회 실패: eventType={} paymentKey={}", eventType, paymentKey)
            return ResponseEntity.ok().build()
        }

        log.info("토스 웹훅 수신 (PG 재조회로 검증됨): eventType={} paymentKey={} verifiedStatus={}",
            eventType, paymentKey, verified.status)
        // TODO: eventType별 실제 처리(가상계좌 입금 확정 등)를 추가할 때는 반드시 위 verified.status를
        // 기준으로 분기할 것 — payload["data"] 안의 상태 필드는 서명 검증이 안 되므로 신뢰하지 않는다.

        return ResponseEntity.ok().build()
    }
}
