package com.monticker.api.subscription.application

import com.monticker.api.subscription.domain.*
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.SubscriptionPlanRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.subscription.infrastructure.pg.PgClient
import com.monticker.api.subscription.infrastructure.pg.PaymentRequest
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
            record.markSuccess(result.pgTransactionId!!)
            paymentRepo.save(record)

            val subscription = getOrCreateSubscription(userId, plan)
            subscription.upgrade(plan, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
            subscriptionRepo.save(subscription)

            log.info("구독 활성화: userId={} plan={} txId={}", userId, planCode, result.pgTransactionId)
            SubscribeResult.success(planCode, paymentId = record.id)
        } else {
            record.markFailed(result.failureReason ?: "PG 결제 실패")
            paymentRepo.save(record)
            log.warn("결제 실패: userId={} plan={} reason={}", userId, planCode, result.failureReason)
            SubscribeResult.failure(planCode, result.failureReason ?: "결제 처리 중 오류가 발생했습니다.")
        }
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
     */
    @Transactional
    fun renewSubscription(subscription: UserSubscription): RenewResult {
        val plan = subscription.plan
        if (plan.price.toLong() == 0L) return RenewResult.Skipped

        val record = paymentRepo.save(
            PaymentRecord(userId = subscription.userId, plan = plan, amount = plan.price)
        )
        val result = pgClient.requestPayment(
            PaymentRequest(userId = subscription.userId, planCode = plan.code.name, amount = plan.price)
        )

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
