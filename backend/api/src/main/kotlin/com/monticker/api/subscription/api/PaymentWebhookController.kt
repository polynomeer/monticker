package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.common.aop.RateLimited
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.PaymentStatus
import com.monticker.api.subscription.domain.PlanCode
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
    private val subscriptionService: SubscriptionService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class PrepareRequest(val planCode: String)

    data class PrepareResponse(
        val orderId: String,
        val amount: BigDecimal,
        val planCode: String,
    )

    /** confirm 은 orderId 만 받는다. 금액은 서버가 준비 시점에 정한 값을 쓴다 (ADR-059). */
    data class ConfirmRequest(
        val paymentKey: String,
        val orderId: String,
    )

    data class ConfirmResponse(
        val success: Boolean,
        val pgTransactionId: String?,
        val message: String?,
    )

    /**
     * 결제 준비 — 프론트가 토스 SDK 를 띄우기 **전에** 호출해 orderId 와 금액을 받는다.
     *
     * 예전에는 이 단계가 없어서 프론트가 orderId 와 amount 를 스스로 만들어 confirm 에 실어
     * 보냈다. 토스 confirm 은 "보낸 금액이 실제 결제 금액과 같은가"만 검증하므로, 100원을
     * 결제하고 `{amount:100, planCode:"PRO"}` 로 confirm 하면 PRO 가 활성화됐다 — 플랜 가격은
     * 기록에만 쓰였다. 이제 금액과 orderId 를 서버가 쥔다.
     */
    @PostMapping("/prepare")
    @RateLimited(limit = 20, windowSec = 3600, keyPrefix = "payment.prepare")
    fun prepare(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: PrepareRequest,
    ): ResponseEntity<PrepareResponse> {
        val userId = jwtTokenProvider.getUserId(token.removePrefix("Bearer "))
        val planCode = runCatching { PlanCode.valueOf(req.planCode.uppercase()) }.getOrElse {
            return ResponseEntity.badRequest().build()
        }
        val prepared = subscriptionService.preparePayment(userId, planCode)
        return ResponseEntity.ok(
            PrepareResponse(prepared.orderId, prepared.amount, prepared.planCode.name)
        )
    }

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

        // 준비된 주문만 확정한다. 남의 orderId 로는 조회되지 않는다.
        val prepared = subscriptionService.findPreparedPayment(userId, req.orderId)
            ?: return ResponseEntity.badRequest().body(
                ConfirmResponse(false, null, "준비되지 않은 주문입니다. 결제를 다시 시작해주세요.")
            )

        // 이미 확정된 주문이면 PG 를 다시 찌르지 않는다 — 네트워크 재시도로 confirm 이 두 번
        // 도착하는 경우다. 토스는 2회차를 4xx 로 거절하는데, 예전에는 그걸 "결제 실패"로 읽고
        // 400 을 돌려줬다. 실제로는 성공한 결제인데도.
        if (prepared.status == PaymentStatus.SUCCESS) {
            log.info("confirm 재수신 (이미 확정): userId={} orderId={}", userId, req.orderId)
            return ResponseEntity.ok(ConfirmResponse(true, prepared.pgTransactionId, null))
        }

        log.info("결제 확정 요청: userId={} plan={} orderId={}", userId, prepared.plan.code, req.orderId)

        val result = tossPgClient.confirmPayment(
            paymentKey = req.paymentKey,
            orderId    = req.orderId,
            amount     = prepared.amount,     // 클라이언트가 보낸 금액을 쓰지 않는다
        )

        // 확정이 실패했어도 끝이 아니다. PG 에 직접 물어 실제로 이미 승인된 건이면
        // (confirm 중복 호출·응답 유실) 성공으로 이어간다 — 그래야 "돈은 빠졌는데 구독은
        // 없다"가 생기지 않는다.
        val pgTransactionId = result.pgTransactionId ?: run {
            val verified = tossPgClient.getPaymentStatus(req.paymentKey)
            if (verified.found && verified.status == "DONE" && verified.totalAmount == prepared.amount) {
                log.warn("confirm 은 실패했지만 PG 재조회 결과 이미 승인됨: orderId={}", req.orderId)
                verified.paymentKey ?: req.paymentKey
            } else {
                log.warn("결제 확정 실패: userId={} orderId={} reason={} (PG 상태={})",
                    userId, req.orderId, result.failureReason, verified.status)
                return ResponseEntity.badRequest().body(
                    ConfirmResponse(false, null, result.failureReason)
                )
            }
        }

        // subscribe()가 아니라 activateConfirmedSubscription()을 쓴다 — 결제는 위에서 이미
        // 확정됐으므로 pgClient.requestPayment()를 다시 태우면 안 된다(TossPgClient는 그 경로를
        // 스텁으로 항상 실패 처리하도록 만들어져 있어, 그러면 실제로 결제된 고객의 구독이
        // 활성화되지 않는다 — 과거에 실제로 있던 버그).
        val subscribeResult = subscriptionService.activateConfirmedSubscription(
            userId = userId, orderId = req.orderId, pgTransactionId = pgTransactionId,
        )

        return if (subscribeResult.success) {
            ResponseEntity.ok(ConfirmResponse(success = true, pgTransactionId = pgTransactionId, message = null))
        } else {
            log.error("결제는 확정됐으나 구독 활성화 실패: userId={} orderId={} reason={}",
                userId, req.orderId, subscribeResult.errorMessage)
            ResponseEntity.internalServerError().body(
                ConfirmResponse(success = false, pgTransactionId = pgTransactionId, message = subscribeResult.errorMessage)
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
