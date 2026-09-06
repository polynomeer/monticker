package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.common.aop.RateLimited
import com.monticker.api.subscription.domain.UserBillingKey
import com.monticker.api.subscription.infrastructure.UserBillingKeyRepository
import com.monticker.api.subscription.infrastructure.pg.PgClient
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * 정기결제(자동 갱신) 카드 등록/조회/해지. PaymentWebhookController와 달리 mock/real 양쪽
 * 모드에서 전부 동작한다 — PgClient가 인터페이스라 Mock/Toss 중 활성화된 구현체를 그대로 쓴다
 * (SubscriptionController와 동일한 방식).
 *
 * 등록 플로우:
 *   1. 프론트가 POST /customer-key로 customerKey를 받는다 (최초 1회 발급, 이후 재사용).
 *   2. 프론트가 토스 SDK requestBillingAuth(customerKey, ...)로 카드 등록 위젯을 띄운다.
 *   3. successUrl로 {authKey, customerKey}를 받으면 POST /register로 백엔드에 전달한다.
 */
@RestController
@RequestMapping("/api/subscription/billing")
class BillingController(
    private val pgClient: PgClient,
    private val billingKeyRepo: UserBillingKeyRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    data class CustomerKeyResponse(val customerKey: String)
    data class RegisterBillingRequest(val authKey: String, val customerKey: String)
    data class BillingStatusResponse(val registered: Boolean, val cardCompany: String?, val cardLast4: String?)

    /**
     * 토스 위젯에 넘길 customerKey를 발급(최초 1회)하거나 기존 값을 재사용한다.
     * 위젯 호출 전에 반드시 이 값을 먼저 받아야 한다.
     */
    @GetMapping("/customer-key")
    fun getOrCreateCustomerKey(@RequestHeader("Authorization") token: String): ResponseEntity<CustomerKeyResponse> {
        val uid = userId(token)
        val customerKey = billingKeyRepo.findByUserId(uid)
            .map { it.customerKey }
            .orElseGet { "user_${uid}_${UUID.randomUUID()}" }
        return ResponseEntity.ok(CustomerKeyResponse(customerKey))
    }

    /**
     * 위젯 successUrl에서 받은 {authKey, customerKey}로 billingKey를 발급받아 저장한다.
     * 카드를 재등록하면 기존 레코드를 덮어쓴다(사용자당 하나의 활성 카드만 유지).
     */
    @PostMapping("/register")
    @RateLimited(limit = 10, windowSec = 3600, keyPrefix = "billing.register")
    @Transactional
    fun register(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: RegisterBillingRequest,
    ): ResponseEntity<BillingStatusResponse> {
        val uid = userId(token)
        val result = pgClient.issueBillingKey(req.authKey, req.customerKey)

        if (!result.success || result.billingKey == null) {
            log.warn("빌링키 등록 실패: userId={} reason={}", uid, result.failureReason)
            return ResponseEntity.badRequest().body(BillingStatusResponse(false, null, null))
        }

        val existing = billingKeyRepo.findByUserId(uid).orElse(null)
        if (existing != null) {
            existing.customerKey = req.customerKey
            existing.replace(result.billingKey, result.cardCompany, result.cardLast4)
            billingKeyRepo.save(existing)
        } else {
            billingKeyRepo.save(
                UserBillingKey(
                    userId = uid,
                    customerKey = req.customerKey,
                    billingKeyValue = result.billingKey,
                    cardCompany = result.cardCompany,
                    cardLast4 = result.cardLast4,
                )
            )
        }

        log.info("빌링키 등록 성공: userId={} cardCompany={}", uid, result.cardCompany)
        return ResponseEntity.ok(BillingStatusResponse(true, result.cardCompany, result.cardLast4))
    }

    @GetMapping
    fun getStatus(@RequestHeader("Authorization") token: String): ResponseEntity<BillingStatusResponse> {
        val billingKey = billingKeyRepo.findByUserId(userId(token)).orElse(null)
        return ResponseEntity.ok(
            if (billingKey != null) BillingStatusResponse(true, billingKey.cardCompany, billingKey.cardLast4)
            else BillingStatusResponse(false, null, null)
        )
    }

    /**
     * 자동 갱신 카드 등록 해지 — 이후 갱신 배치는 결제 실패로 처리되어 기존 3회 실패 다운그레이드
     * 로직을 탄다.
     *
     * @Transactional이 꼭 필요하다 — deleteByUserId처럼 파생된(derived) delete 쿼리는
     * deleteById()와 달리 리포지토리 프록시가 자체적으로 트랜잭션을 열어주지 않는다.
     * 실제로 이 어노테이션 없이 호출하면 "No EntityManager with actual transaction available"
     * 예외로 500이 나는 것을 직접 재현해서 확인했다.
     */
    @DeleteMapping
    @Transactional
    fun deregister(@RequestHeader("Authorization") token: String): ResponseEntity<Void> {
        billingKeyRepo.deleteByUserId(userId(token))
        return ResponseEntity.noContent().build()
    }
}
