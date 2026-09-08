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
 * 일회성 결제 플로우:
 *   1. 프론트엔드에서 토스 SDK로 결제 위젯 표시 → 결제 승인 대기
 *   2. 프론트엔드가 paymentKey, orderId, amount를 백엔드에 전달
 *   3. 백엔드(여기)가 /v1/payments/confirm 호출 → 최종 승인
 *
 * 정기결제(자동 갱신) 플로우 — 별도 API, confirm과 무관:
 *   1. 프론트엔드에서 토스 SDK `requestBillingAuth()` 위젯으로 카드 등록
 *   2. successUrl로 {authKey, customerKey} 리다이렉트 → 백엔드(BillingController.register)에 전달
 *   3. 백엔드(여기)가 /v1/billing/authorizations/issue 호출 → billingKey 발급받아 저장
 *   4. 갱신 시점마다 /v1/billing/{billingKey} 호출 → 저장된 카드로 자동 청구
 *      (SubscriptionService.renewSubscription 참고)
 *
 * 참조: https://docs.tosspayments.com/reference, https://docs.tosspayments.com/guides/v2/billing/integration
 */
@Component
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "false")
class TossPgClient(
    // 실제 프로퍼티 경로는 app.pg.toss.secret-key다(application.yml/application-prod.yml 참고).
    // "app.toss.secret-key"로 잘못 참조되어 있었던 적이 있다 — 그 경로는 어디에도 정의돼
    // 있지 않아서 PG_MOCK_ENABLED=false로 부팅하면 항상 PlaceholderResolutionException으로
    // 죽었다(실제 재현: 로컬에서 PG_MOCK_ENABLED=false로 부팅해서 확인).
    @Value("\${app.pg.toss.secret-key}") private val secretKey: String,
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

    /**
     * 웹훅 바디를 믿지 않고 PG에 직접 재조회해 권위 있는 상태를 얻는다 (PgClient.getPaymentStatus 참고).
     */
    override fun getPaymentStatus(paymentKey: String): PaymentStatusResult {
        return try {
            val response = restClient.get()
                .uri("/v1/payments/$paymentKey")
                .retrieve()
                .body(TossConfirmResponse::class.java)
                ?: return PaymentStatusResult(found = false)

            PaymentStatusResult(found = true, status = response.status, totalAmount = response.totalAmount.toBigDecimal())
        } catch (e: RestClientException) {
            log.error("[TossPG] 결제 상태 조회 실패: paymentKey={} error={}", paymentKey, e.message)
            PaymentStatusResult(found = false)
        }
    }

    /**
     * 정기결제 카드 등록 — 프론트가 토스 SDK `requestBillingAuth()` 위젯으로 카드 인증을
     * 마치면 successUrl에 {authKey, customerKey}가 리다이렉트된다. authKey는 1회용이라
     * 여기서 실제 billingKey로 교환해야 한다(교환 안 하면 그대로 소멸).
     */
    override fun issueBillingKey(authKey: String, customerKey: String): BillingKeyResult {
        return try {
            val body = mapOf("authKey" to authKey, "customerKey" to customerKey)
            val response = restClient.post()
                .uri("/v1/billing/authorizations/issue")
                .body(body)
                .retrieve()
                .body(TossBillingResponse::class.java)
                ?: return BillingKeyResult(success = false, failureReason = "빌링키 발급 응답이 없습니다")

            log.info("[TossPG] 빌링키 발급 성공: customerKey={}", customerKey)
            BillingKeyResult(
                success = true,
                billingKey = response.billingKey,
                cardCompany = response.card?.company,
                cardLast4 = response.card?.number?.takeLast(4),
            )
        } catch (e: RestClientException) {
            log.error("[TossPG] 빌링키 발급 실패: customerKey={} error={}", customerKey, e.message)
            BillingKeyResult(success = false, failureReason = e.message)
        }
    }

    /** 저장된 billingKey로 자동결제 실행 — 정기결제 갱신 배치가 호출한다. */
    override fun chargeBilling(
        billingKey: String,
        customerKey: String,
        amount: BigDecimal,
        orderId: String,
        orderName: String,
    ): PaymentResult {
        return try {
            val body = mapOf(
                "customerKey" to customerKey,
                "amount"      to amount.toLong(),
                "orderId"     to orderId,
                "orderName"   to orderName,
            )
            val response = restClient.post()
                .uri("/v1/billing/$billingKey")
                .body(body)
                .retrieve()
                .body(TossConfirmResponse::class.java)

            if (response?.status == "DONE") {
                log.info("[TossPG] 정기결제 성공: orderId={} amount={}", orderId, amount)
                PaymentResult(success = true, pgTransactionId = response.paymentKey)
            } else {
                log.warn("[TossPG] 정기결제 상태 이상: status={}", response?.status)
                PaymentResult(success = false, failureReason = "결제 상태 이상: ${response?.status}")
            }
        } catch (e: RestClientException) {
            log.error("[TossPG] 정기결제 실패: orderId={} error={}", orderId, e.message)
            PaymentResult(success = false, failureReason = e.message)
        }
    }

    private data class TossConfirmResponse(
        val paymentKey: String,
        val orderId: String,
        val status: String,       // READY | IN_PROGRESS | WAITING_FOR_DEPOSIT | DONE | CANCELED | PARTIAL_CANCELED | ABORTED | EXPIRED
        val totalAmount: Long,
        val method: String?,
    )

    private data class TossBillingResponse(
        val billingKey: String,
        val customerKey: String,
        val card: TossCardInfo?,
    )

    private data class TossCardInfo(
        val company: String?,
        val number: String?,   // 마스킹된 카드번호(예: "1234-56**-****-7890") — 뒤 4자리만 표시용으로 취함
    )
}
