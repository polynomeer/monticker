package com.monticker.api.subscription.infrastructure.pg

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.util.Base64

/**
 * 토스페이먼츠 결제 클라이언트.
 *
 * 활성화 조건: app.pg.mock.enabled=false (프로덕션 기본값)
 *
 * 필요 환경변수:
 *   TOSS_SECRET_KEY  — 토스페이먼츠 시크릿 키 (test_sk_... 또는 live_sk_...)
 *
 * 플로우:
 *   1. 프론트엔드에서 토스 SDK로 결제 위젯 표시 → 결제 승인 대기
 *   2. 프론트엔드가 paymentKey, orderId, amount를 백엔드에 전달
 *   3. 백엔드(여기)가 /v1/payments/confirm 호출 → 최종 승인
 *
 * 참조: https://docs.tosspayments.com/reference
 */
@Component
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "false")
class TossPgClient(
    @Value("\${app.toss.secret-key}") private val secretKey: String,
) : PgClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient = RestClient.builder()
        .baseUrl("https://api.tosspayments.com")
        .defaultHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("$secretKey:".toByteArray()))
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun requestPayment(request: PaymentRequest): PaymentResult {
        // 토스페이먼츠는 프론트에서 SDK 호출 → 백엔드 confirm 구조.
        // 이 메서드는 [프론트 SDK 결제 완료 후] paymentKey가 웹훅으로 전달된 시점에 confirm을 수행한다.
        // 실제 운영에서는 PaymentWebhookController.confirm() 참조.
        log.warn("[TossPG] requestPayment는 웹훅 플로우를 사용하세요. PaymentWebhookController.confirm() 참조")
        return PaymentResult(success = false, failureReason = "토스페이먼츠는 웹훅 기반 confirm 플로우를 사용합니다")
    }

    /**
     * 토스페이먼츠 결제 확정 — 프론트 SDK 결제 완료 후 호출.
     *
     * @param paymentKey 토스 결제 키
     * @param orderId 주문 ID (서버 생성)
     * @param amount 결제 금액 (원 단위)
     */
    fun confirmPayment(paymentKey: String, orderId: String, amount: BigDecimal): PaymentResult {
        return try {
            val body = mapOf(
                "paymentKey" to paymentKey,
                "orderId"    to orderId,
                "amount"     to amount.toLong(),
            )

            val response = restClient.post()
                .uri("/v1/payments/confirm")
                .body(body)
                .retrieve()
                .body(TossConfirmResponse::class.java)

            if (response?.status == "DONE") {
                log.info("[TossPG] 결제 확정 성공: paymentKey={} orderId={} amount={}", paymentKey, orderId, amount)
                PaymentResult(success = true, pgTransactionId = response.paymentKey)
            } else {
                log.warn("[TossPG] 결제 확정 비정상: status={}", response?.status)
                PaymentResult(success = false, failureReason = "결제 상태 이상: ${response?.status}")
            }
        } catch (e: RestClientException) {
            log.error("[TossPG] 결제 확정 실패: {}", e.message)
            PaymentResult(success = false, failureReason = e.message)
        }
    }

    override fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult {
        return try {
            val body = mapOf(
                "cancelReason" to "사용자 환불 요청",
                "cancelAmount" to amount.toLong(),
            )

            restClient.post()
                .uri("/v1/payments/$pgTransactionId/cancel")
                .body(body)
                .retrieve()
                .toBodilessEntity()

            log.info("[TossPG] 환불 성공: paymentKey={} amount={}", pgTransactionId, amount)
            RefundResult(success = true)
        } catch (e: RestClientException) {
            log.error("[TossPG] 환불 실패: paymentKey={} error={}", pgTransactionId, e.message)
            RefundResult(success = false, failureReason = e.message)
        }
    }

    private data class TossConfirmResponse(
        val paymentKey: String,
        val orderId: String,
        val status: String,       // READY | IN_PROGRESS | WAITING_FOR_DEPOSIT | DONE | CANCELED | PARTIAL_CANCELED | ABORTED | EXPIRED
        val totalAmount: Long,
        val method: String?,
    )
}
