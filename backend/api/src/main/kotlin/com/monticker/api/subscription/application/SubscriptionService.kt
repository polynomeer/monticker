package com.monticker.api.subscription.application

import com.monticker.api.subscription.domain.*
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.SubscriptionPlanRepository
import com.monticker.api.subscription.infrastructure.UserBillingKeyRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.subscription.infrastructure.pg.PgClient
import com.monticker.api.subscription.infrastructure.pg.PaymentRequest
import com.monticker.api.subscription.infrastructure.pg.PaymentResult
import com.monticker.api.wallet.application.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class SubscriptionService(
    private val planRepo: SubscriptionPlanRepository,
    private val subscriptionRepo: UserSubscriptionRepository,
    private val paymentRepo: PaymentRecordRepository,
    private val pgClient: PgClient,
    private val ledgerService: LedgerService,
    private val billingKeyRepo: UserBillingKeyRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getActivePlans(): List<SubscriptionPlan> =
        planRepo.findAllByIsActiveTrue()

    @Transactional(readOnly = true)
    fun getMySubscription(userId: Long): UserSubscription? =
        subscriptionRepo.findByUserId(userId).orElse(null)

    @Transactional
    fun subscribe(userId: Long, planCode: PlanCode): SubscribeResult {
        val plan = planRepo.findByCode(planCode).orElseThrow {
            IllegalArgumentException("존재하지 않는 플랜: $planCode")
        }

        if (plan.price.toLong() == 0L) {
            // 무료 플랜은 PG 결제 없이 즉시 적용
            val subscription = getOrCreateSubscription(userId, plan)
            subscription.upgrade(plan, expiresAt = Instant.now().plus(36500, ChronoUnit.DAYS))
            subscriptionRepo.save(subscription)
            return SubscribeResult.success(planCode, paymentId = null)
        }

        val record = paymentRepo.save(
            PaymentRecord(userId = userId, plan = plan, amount = plan.price)
        )

        val result = pgClient.requestPayment(
            PaymentRequest(userId = userId, planCode = planCode.name, amount = plan.price)
        )

        return if (result.success) {
            activatePaidPlan(userId, plan, record, result.pgTransactionId!!)
        } else {
            record.markFailed(result.failureReason ?: "PG 결제 실패")
            paymentRepo.save(record)
            log.warn("결제 실패: userId={} plan={} reason={}", userId, planCode, result.failureReason)
            SubscribeResult.failure(planCode, result.failureReason ?: "결제 처리 중 오류가 발생했습니다.")
        }
    }

    /**
     * 토스페이먼츠 confirm 플로우 전용(PaymentWebhookController.confirm 참고). 프론트가
     * 토스 SDK로 결제를 이미 완료했고, 컨트롤러가 tossPgClient.confirmPayment()로 그 결제를
     * 이미 확정한 뒤 호출한다.
     *
     * subscribe()처럼 pgClient.requestPayment()를 다시 호출하면 안 된다 —
     * TossPgClient.requestPayment()는 "웹훅 플로우를 쓰라"는 스텁이라 항상 실패를 반환하므로,
     * 여기서 다시 호출하면 방금 실제로 성공한 결제인데도 구독이 활성화되지 않고 PaymentRecord만
     * FAILED로 남는다 — 실제 코드에 있던 버그(고객은 결제됐는데 서비스는 활성화 안 됨).
     */
    @Transactional
    fun activateConfirmedSubscription(userId: Long, planCode: PlanCode, pgTransactionId: String): SubscribeResult {
        val plan = planRepo.findByCode(planCode).orElseThrow {
            IllegalArgumentException("존재하지 않는 플랜: $planCode")
        }
        val record = paymentRepo.save(PaymentRecord(userId = userId, plan = plan, amount = plan.price))
        return activatePaidPlan(userId, plan, record, pgTransactionId)
    }

    private fun activatePaidPlan(
        userId: Long,
        plan: SubscriptionPlan,
        record: PaymentRecord,
        pgTransactionId: String,
    ): SubscribeResult {
        record.markSuccess(pgTransactionId)
        paymentRepo.save(record)

        val subscription = getOrCreateSubscription(userId, plan)
        subscription.upgrade(plan, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
        subscriptionRepo.save(subscription)

        log.info("구독 활성화: userId={} plan={} txId={}", userId, plan.code, pgTransactionId)
        ledgerService.recordSubscriptionPayment(userId, plan.code.name, plan.price, record.id)
        return SubscribeResult.success(plan.code, paymentId = record.id)
    }

    @Transactional
    fun cancel(userId: Long) {
        val subscription = subscriptionRepo.findByUserId(userId).orElseThrow {
            IllegalStateException("구독 정보가 없습니다.")
        }
        require(subscription.status == SubscriptionStatus.ACTIVE) { "활성 구독이 없습니다." }
        subscription.cancel()
        subscriptionRepo.save(subscription)
        log.info("구독 해지: userId={} plan={}", userId, subscription.plan.code)
    }

    @Transactional(readOnly = true)
    fun getPayments(userId: Long, pageable: Pageable): Page<PaymentRecord> =
        paymentRepo.findAllByUserIdOrderByCreatedAtDesc(userId, pageable)

    /**
     * 월 갱신 배치에서 호출 — 만료 예정 구독을 재결제 시도.
     * 3회 실패 시 FREE로 다운그레이드.
     *
     * 예전에는 여기서도 pgClient.requestPayment()를 호출했는데, TossPgClient에서는 그게
     * 항상 실패하는 스텁이라(실제 결제는 confirm/billing 전용 API로만 가능) 실제 운영에서는
     * 정기결제가 단 한 번도 성공할 수 없는 구조였다 — 애초에 자동결제 자체가 구현되어 있지
     * 않았던 것. 이제는 등록된 빌링키(UserBillingKey)로 pgClient.chargeBilling()을 호출한다.
     * 빌링키가 없으면(자동결제 카드 미등록) 결제 시도 자체가 불가능하므로 결제 실패로
     * 취급해 기존 3회 실패 다운그레이드 로직을 그대로 태운다.
     */
    @Transactional
    fun renewSubscription(subscription: UserSubscription): RenewResult {
        val plan = subscription.plan
        if (plan.price.toLong() == 0L) return RenewResult.Skipped

        val record = paymentRepo.save(
            PaymentRecord(userId = subscription.userId, plan = plan, amount = plan.price)
        )
        val billingKey = billingKeyRepo.findByUserId(subscription.userId).orElse(null)
        val result = if (billingKey == null) {
            PaymentResult(success = false, failureReason = "등록된 자동결제 카드가 없습니다.")
        } else {
            pgClient.chargeBilling(
                billingKey  = billingKey.billingKeyValue,
                customerKey = billingKey.customerKey,
                amount      = plan.price,
                orderId     = "renewal_${subscription.id}_${System.currentTimeMillis()}",
                orderName   = "${plan.name} 정기결제",
            )
        }

        return if (result.success) {
            record.markSuccess(result.pgTransactionId!!)
            paymentRepo.save(record)
            subscription.upgrade(plan, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
            subscriptionRepo.save(subscription)
            log.info("구독 갱신 성공: userId={} plan={}", subscription.userId, plan.code)
            RenewResult.Renewed
        } else {
            record.markFailed(result.failureReason ?: "갱신 결제 실패")
            paymentRepo.save(record)
            val failedCount = paymentRepo
                .findAllByUserIdOrderByCreatedAtDesc(subscription.userId, Pageable.ofSize(3))
                .count { it.status == PaymentStatus.FAILED }

            if (failedCount >= 3) {
                val freePlan = planRepo.findByCode(PlanCode.FREE).orElseThrow()
                subscription.downgradeToFree(freePlan)
                subscriptionRepo.save(subscription)
                log.warn("구독 다운그레이드 (3회 실패): userId={}", subscription.userId)
                RenewResult.Downgraded
            } else {
                RenewResult.Failed
            }
        }
    }

    private fun getOrCreateSubscription(userId: Long, plan: SubscriptionPlan): UserSubscription =
        subscriptionRepo.findByUserId(userId).orElseGet {
            subscriptionRepo.save(UserSubscription(userId = userId, plan = plan))
        }
}

data class SubscribeResult(
    val success: Boolean,
    val planCode: PlanCode,
    val paymentId: Long?,
    val errorMessage: String? = null,
) {
    companion object {
        fun success(planCode: PlanCode, paymentId: Long?) = SubscribeResult(true, planCode, paymentId)
        fun failure(planCode: PlanCode, message: String) = SubscribeResult(false, planCode, null, message)
    }
}

sealed interface RenewResult {
    data object Renewed    : RenewResult
    data object Failed     : RenewResult
    data object Downgraded : RenewResult
    data object Skipped    : RenewResult
}
