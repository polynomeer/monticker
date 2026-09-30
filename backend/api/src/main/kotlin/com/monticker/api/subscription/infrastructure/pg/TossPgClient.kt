package com.monticker.api.subscription.infrastructure.pg

import com.monticker.api.common.http.HttpTimeouts
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
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
 * ADR-053 — 이 클라이언트의 모든 호출은 서킷브레이커 `tossPg`를 통과하고, 실패는
 * DECLINED / UNAVAILABLE / INDETERMINATE 로 분류해서 돌려준다. 예전에는 전부
 * `success=false` 하나로 합쳐져서, 호출부가 "카드 거절"과 "PG 장애"를 구분할 수 없었다.
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
    // 하드코딩돼 있던 값을 프로퍼티로 뺐다 — 스텁을 PG 자리에 세우지 못하면
    // 카오스 실험(CH-13/CH-14)을 아예 돌릴 수 없다. 기본값은 실서버 그대로다.
    @Value("\${app.pg.toss.base-url:https://api.tosspayments.com}") private val baseUrl: String,
    cbRegistry: CircuitBreakerRegistry,
) : PgClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("tossPg")

    // 결제 승인은 카드사 경유로 브로커보다 느릴 수 있지만 무제한은 아니다 (P0-2).
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(HttpTimeouts.requestFactory(HttpTimeouts.PAYMENT_READ))
        .defaultHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("$secretKey:".toByteArray()))
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    // ── 실패 분류 ────────────────────────────────────────────────────────────
    //
    // 여기서 틀리면 뒤가 전부 틀린다. 특히 INDETERMINATE를 DECLINED로 잘못 읽으면
    // "이미 청구된 건을 다시 청구"하게 되고, 반대로 UNAVAILABLE을 DECLINED로 읽으면
    // "PG 장애 때문에 고객을 강등"하게 된다.

    private fun classify(e: Throwable): PaymentFailureKind = when {
        e is CallNotPermittedException -> PaymentFailureKind.UNAVAILABLE
        // 연결조차 못 했다 = 요청이 PG에 닿지 않았다 = 청구되지 않았음이 확실하다.
        e is ResourceAccessException && (e.cause is ConnectException || e.cause is HttpConnectTimeoutException) ->
            PaymentFailureKind.UNAVAILABLE
        // 연결은 됐는데 응답을 못 받았다(read 타임아웃, 소켓 끊김) — 청구 여부를 알 수 없다.
        e is ResourceAccessException -> PaymentFailureKind.INDETERMINATE
        // PG 내부 오류. 승인 직후 장애일 수도 있으므로 역시 불확정이다.
        e is HttpServerErrorException -> PaymentFailureKind.INDETERMINATE
        // 4xx는 PG가 요청을 이해하고 거절한 것이다(잔액부족, 카드오류, 중복 orderId 등).
        e is HttpClientErrorException -> PaymentFailureKind.DECLINED
        else -> PaymentFailureKind.DECLINED
    }

    private fun failure(op: String, e: Throwable): PaymentResult {
        val kind = classify(e)
        log.error("[TossPG] {} 실패 ({}): {}", op, kind, e.message)
        return PaymentResult(success = false, failureReason = e.message, failureKind = kind)
    }

    /** CB를 통과시키되 예외는 그대로 위로 던진다 — 삼키면 브레이커가 실패를 집계하지 못한다. */
    private fun <T> guarded(block: () -> T): T = cb.executeCallable(block)

    // ── 일회성 결제 ──────────────────────────────────────────────────────────

    override fun requestPayment(request: PaymentRequest): PaymentResult {
        // 토스페이먼츠는 프론트에서 SDK 호출 → 백엔드 confirm 구조.
        // 이 메서드는 [프론트 SDK 결제 완료 후] paymentKey가 웹훅으로 전달된 시점에 confirm을 수행한다.
        // 실제 운영에서는 PaymentWebhookController.confirm() 참조.
        log.warn("[TossPG] requestPayment는 웹훅 플로우를 사용하세요. PaymentWebhookController.confirm() 참조")
        return PaymentResult(
            success = false,
            failureReason = "토스페이먼츠는 웹훅 기반 confirm 플로우를 사용합니다",
            failureKind = PaymentFailureKind.DECLINED,
        )
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
            val response = guarded {
                restClient.post()
                    .uri("/v1/payments/confirm")
                    .body(mapOf("paymentKey" to paymentKey, "orderId" to orderId, "amount" to amount.toLong()))
                    .retrieve()
                    .body(TossConfirmResponse::class.java)
            }

            if (response?.status == "DONE") {
                log.info("[TossPG] 결제 확정 성공: paymentKey={} orderId={} amount={}", paymentKey, orderId, amount)
                PaymentResult(success = true, pgTransactionId = response.paymentKey)
            } else {
                log.warn("[TossPG] 결제 확정 비정상: status={}", response?.status)
                PaymentResult(
                    success = false,
                    failureReason = "결제 상태 이상: ${response?.status}",
                    failureKind = PaymentFailureKind.DECLINED,
                )
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:tossPg] 요청 차단됨 — 결제 확정 건너뜀")
            failure("결제 확정", e)
        } catch (e: RestClientException) {
            failure("결제 확정", e)
        }
    }

    override fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult {
        return try {
            guarded {
                restClient.post()
                    .uri("/v1/payments/$pgTransactionId/cancel")
                    .body(mapOf("cancelReason" to "사용자 환불 요청", "cancelAmount" to amount.toLong()))
                    .retrieve()
                    .toBodilessEntity()
            }
            log.info("[TossPG] 환불 성공: paymentKey={} amount={}", pgTransactionId, amount)
            RefundResult(success = true)
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:tossPg] 요청 차단됨 — 환불 건너뜀")
            RefundResult(success = false, failureReason = "PG 장애로 서킷브레이커가 열려 있습니다.")
        } catch (e: RestClientException) {
            log.error("[TossPG] 환불 실패: paymentKey={} error={}", pgTransactionId, e.message)
            RefundResult(success = false, failureReason = e.message)
        }
    }

    // ── 결제 상태 조회 ───────────────────────────────────────────────────────

    /**
     * 웹훅 바디를 믿지 않고 PG에 직접 재조회해 권위 있는 상태를 얻는다 (PgClient.getPaymentStatus 참고).
     */
    override fun getPaymentStatus(paymentKey: String): PaymentStatusResult =
        lookup("결제 상태 조회", "/v1/payments/$paymentKey")

    override fun findPaymentByOrderId(orderId: String): PaymentStatusResult =
        lookup("orderId 결제 조회", "/v1/payments/orders/$orderId")

    /**
     * 조회 실패와 "결제가 존재하지 않음"을 구분한다 — 이 둘을 섞으면 불확정 상태 복구가
     * 정반대로 동작한다(조회가 실패했을 뿐인데 "결제 안 됐다"로 읽고 재청구).
     * 토스는 존재하지 않는 orderId에 404를 준다.
     */
    private fun lookup(op: String, uri: String): PaymentStatusResult {
        return try {
            val response = guarded {
                restClient.get().uri(uri).retrieve().body(TossConfirmResponse::class.java)
            } ?: return PaymentStatusResult(found = false)

            PaymentStatusResult(
                found = true,
                status = response.status,
                totalAmount = response.totalAmount.toBigDecimal(),
                paymentKey = response.paymentKey,
            )
        } catch (e: HttpClientErrorException.NotFound) {
            // 권위 있는 "없음". 재청구해도 안전하다.
            PaymentStatusResult(found = false)
        } catch (e: Exception) {
            log.error("[TossPG] {} 실패: uri={} error={}", op, uri, e.message)
            PaymentStatusResult(found = false, lookupFailed = true)
        }
    }

    // ── 정기결제 ─────────────────────────────────────────────────────────────

    /**
     * 정기결제 카드 등록 — 프론트가 토스 SDK `requestBillingAuth()` 위젯으로 카드 인증을
     * 마치면 successUrl에 {authKey, customerKey}가 리다이렉트된다. authKey는 1회용이라
     * 여기서 실제 billingKey로 교환해야 한다(교환 안 하면 그대로 소멸).
     */
    override fun issueBillingKey(authKey: String, customerKey: String): BillingKeyResult {
        return try {
            val response = guarded {
                restClient.post()
                    .uri("/v1/billing/authorizations/issue")
                    .body(mapOf("authKey" to authKey, "customerKey" to customerKey))
                    .retrieve()
                    .body(TossBillingResponse::class.java)
            } ?: return BillingKeyResult(success = false, failureReason = "빌링키 발급 응답이 없습니다")

            log.info("[TossPG] 빌링키 발급 성공: customerKey={}", customerKey)
            BillingKeyResult(
                success = true,
                billingKey = response.billingKey,
                cardCompany = response.card?.company,
                cardLast4 = response.card?.number?.takeLast(4),
            )
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:tossPg] 요청 차단됨 — 빌링키 발급 건너뜀")
            BillingKeyResult(success = false, failureReason = "PG 장애로 서킷브레이커가 열려 있습니다.")
        } catch (e: RestClientException) {
            log.error("[TossPG] 빌링키 발급 실패: customerKey={} error={}", customerKey, e.message)
            BillingKeyResult(success = false, failureReason = e.message)
        }
    }

    /**
     * 저장된 billingKey로 자동결제 실행 — 정기결제 갱신 배치가 호출한다.
     *
     * orderId는 호출부가 재시도 간에 동일한 값을 주기로 약속돼 있다(ADR-053). 토스는 같은
     * orderId로 두 번 청구하지 않고 4xx로 거절하므로, 그 약속이 이중청구를 막는 마지막 방어선이다.
     */
    override fun chargeBilling(
        billingKey: String,
        customerKey: String,
        amount: BigDecimal,
        orderId: String,
        orderName: String,
    ): PaymentResult {
        return try {
            val response = guarded {
                restClient.post()
                    .uri("/v1/billing/$billingKey")
                    .body(
                        mapOf(
                            "customerKey" to customerKey,
                            "amount" to amount.toLong(),
                            "orderId" to orderId,
                            "orderName" to orderName,
                        )
                    )
                    .retrieve()
                    .body(TossConfirmResponse::class.java)
            }

            if (response?.status == "DONE") {
                log.info("[TossPG] 정기결제 성공: orderId={} amount={}", orderId, amount)
                PaymentResult(success = true, pgTransactionId = response.paymentKey)
            } else {
                log.warn("[TossPG] 정기결제 상태 이상: status={}", response?.status)
                PaymentResult(
                    success = false,
                    failureReason = "결제 상태 이상: ${response?.status}",
                    failureKind = PaymentFailureKind.DECLINED,
                )
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:tossPg] 요청 차단됨 — 정기결제 건너뜀 (orderId={})", orderId)
            failure("정기결제", e)
        } catch (e: RestClientException) {
            failure("정기결제", e)
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
