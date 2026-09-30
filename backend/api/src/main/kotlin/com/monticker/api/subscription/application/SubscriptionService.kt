package com.monticker.api.subscription.application

import com.monticker.api.subscription.domain.*
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.SubscriptionPlanRepository
import com.monticker.api.subscription.infrastructure.UserBillingKeyRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.subscription.infrastructure.pg.PgClient
import com.monticker.api.subscription.infrastructure.pg.PaymentFailureKind
import com.monticker.api.subscription.infrastructure.pg.PaymentRequest
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
     * 월 갱신 배치에서 호출 — 만료 예정 구독을 재결제 시도. 연속 3회 실패 시 FREE로 다운그레이드.
     *
     * 예전에는 여기서도 pgClient.requestPayment()를 호출했는데, TossPgClient에서는 그게
     * 항상 실패하는 스텁이라(실제 결제는 confirm/billing 전용 API로만 가능) 실제 운영에서는
     * 정기결제가 단 한 번도 성공할 수 없는 구조였다. 이제는 등록된 빌링키(UserBillingKey)로
     * pgClient.chargeBilling()을 호출한다.
     *
     * ADR-053 — 이 메서드는 **같은 청구주기에 대해 몇 번 호출돼도 한 번만 청구**한다:
     *
     *  1. orderId를 (구독 id, 만료시각)에서 결정적으로 유도한다. 예전의
     *     `renewal_<id>_<millis>`는 재시도마다 값이 바뀌어 토스의 orderId 중복 방어를
     *     통째로 무력화시켰다 — 배치 재실행이 곧 이중청구였다.
     *  2. 그 orderId로 이전 시도를 먼저 찾는다. 이미 성공한 건이면 청구하지 않고
     *     구독 연장만 마저 한다(청구 직후 크래시한 경우).
     *  3. 응답을 못 받은 경우(INDETERMINATE)는 실패로 단정하지 않는다. orderId로 PG에
     *     되물어 실제로 청구됐는지 확인한 뒤에만 판정한다.
     *  4. PG 장애(UNAVAILABLE)는 결제 거절이 아니다 — 다운그레이드 카운트에 넣지 않는다.
     *     PG가 30분 죽었다고 돈 내는 고객을 FREE로 내리면 안 된다.
     */
    @Transactional
    fun renewSubscription(subscription: UserSubscription): RenewResult {
        val plan = subscription.plan
        if (plan.price.toLong() == 0L) return RenewResult.Skipped

        val orderId = renewalOrderId(subscription)
        val existing = paymentRepo.findByPgOrderId(orderId).orElse(null)

        // 이미 이 주기에 청구가 끝났다. 구독 연장만 남았을 수 있다(청구 성공 직후 크래시).
        if (existing?.status == PaymentStatus.SUCCESS) {
            log.info("갱신 재시도 — 이미 결제된 주기다. 청구하지 않고 연장만 확인: orderId={}", orderId)
            extend(subscription, plan)
            return RenewResult.Renewed
        }

        val billingKey = billingKeyRepo.findByUserId(subscription.userId).orElse(null)
            ?: return recordDecline(subscription, plan, orderId, existing, "등록된 자동결제 카드가 없습니다.")

        // 지난 시도가 불확정으로 남아 있으면, 다시 긁기 전에 PG에 먼저 물어본다.
        if (existing != null && existing.status == PaymentStatus.PENDING) {
            when (val recovered = reconcilePending(existing, orderId)) {
                null -> Unit                                  // 청구된 적 없음 — 아래에서 정상 청구
                else -> return recovered
            }
        }

        val record = existing ?: paymentRepo.save(
            PaymentRecord(userId = subscription.userId, plan = plan, amount = plan.price, pgOrderId = orderId)
        )

        val result = pgClient.chargeBilling(
            billingKey  = billingKey.billingKeyValue,
            customerKey = billingKey.customerKey,
            amount      = plan.price,
            orderId     = orderId,
            orderName   = "${plan.name} 정기결제",
        )

        if (result.success) {
            record.markSuccess(result.pgTransactionId!!)
            paymentRepo.save(record)
            extend(subscription, plan)
            log.info("구독 갱신 성공: userId={} plan={}", subscription.userId, plan.code)
            return RenewResult.Renewed
        }

        return when (result.failureKind ?: PaymentFailureKind.DECLINED) {
            // PG에 닿지도 못했다 — 청구되지 않았음이 확실하다. 기록을 PENDING으로 남겨두면
            // 다음 배치가 같은 orderId로 이어서 시도한다. 고객 잘못이 아니므로 카운트하지 않는다.
            PaymentFailureKind.UNAVAILABLE -> {
                log.warn("갱신 보류 (PG 장애): userId={} orderId={} reason={}",
                    subscription.userId, orderId, result.failureReason)
                RenewResult.Deferred
            }
            // 청구됐는지 알 수 없다. 여기서 실패로 단정하고 다음 주기에 다시 긁으면 이중청구다.
            PaymentFailureKind.INDETERMINATE -> {
                log.warn("갱신 불확정 (응답 없음): userId={} orderId={} — PG 재조회로 확인한다",
                    subscription.userId, orderId)
                reconcilePending(record, orderId) ?: RenewResult.Deferred
            }
            PaymentFailureKind.DECLINED ->
                recordDecline(subscription, plan, orderId, record, result.failureReason ?: "갱신 결제 실패")
        }
    }

    /**
     * 불확정 상태를 PG에 되물어 정리한다.
     *
     * @return 판정이 났으면 그 결과, 청구된 적이 없어 그냥 진행하면 되면 null.
     */
    private fun reconcilePending(record: PaymentRecord, orderId: String): RenewResult? {
        val status = pgClient.findPaymentByOrderId(orderId)

        if (status.lookupFailed) {
            // 조회조차 실패했다. "결제 안 됨"으로 읽으면 이중청구로 직행한다 — 판단을 미룬다.
            log.warn("PG 재조회 실패 — 판단 보류: orderId={}", orderId)
            return RenewResult.Deferred
        }
        if (!status.found) return null    // 권위 있는 "없음". 청구해도 안전하다.

        if (status.status == "DONE") {
            log.warn("불확정이었으나 실제로는 청구되어 있었다 — 이중청구를 막았다: orderId={}", orderId)
            record.markSuccess(status.paymentKey ?: orderId)
            paymentRepo.save(record)
            val subscription = subscriptionRepo.findByUserId(record.userId).orElse(null)
                ?: return RenewResult.Renewed
            extend(subscription, record.plan)
            return RenewResult.Renewed
        }

        // CANCELED / ABORTED / EXPIRED — PG가 확정적으로 실패라고 말한다.
        record.markFailed("PG 재조회 결과 ${status.status}")
        paymentRepo.save(record)
        return RenewResult.Failed
    }

    private fun recordDecline(
        subscription: UserSubscription,
        plan: SubscriptionPlan,
        orderId: String,
        existing: PaymentRecord?,
        reason: String,
    ): RenewResult {
        val record = existing ?: paymentRepo.save(
            PaymentRecord(userId = subscription.userId, plan = plan, amount = plan.price, pgOrderId = orderId)
        )
        record.markFailed(reason)
        paymentRepo.save(record)

        if (consecutiveFailures(subscription.userId) >= MAX_RENEWAL_FAILURES) {
            val freePlan = planRepo.findByCode(PlanCode.FREE).orElseThrow()
            subscription.downgradeToFree(freePlan)
            subscriptionRepo.save(subscription)
            log.warn("구독 다운그레이드 ({}회 연속 실패): userId={}", MAX_RENEWAL_FAILURES, subscription.userId)
            return RenewResult.Downgraded
        }
        return RenewResult.Failed
    }

    /** 마지막 성공 이후의 실패 건수. 성공 이력이 없으면 전체 실패 건수다. */
    private fun consecutiveFailures(userId: Long): Long {
        val since = paymentRepo
            .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, PaymentStatus.SUCCESS)
            .map { it.createdAt }
            .orElse(Instant.EPOCH)
        return paymentRepo.countByUserIdAndStatusAndCreatedAtAfter(userId, PaymentStatus.FAILED, since)
    }

    private fun extend(subscription: UserSubscription, plan: SubscriptionPlan) {
        subscription.upgrade(plan, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
        subscriptionRepo.save(subscription)
    }

    companion object {
        const val MAX_RENEWAL_FAILURES = 3L

        /**
         * (구독, 청구주기) → orderId. 같은 주기 안에서는 몇 번을 재시도해도 같은 값이 나와야
         * 토스의 orderId 중복 방어와 우리 유니크 인덱스(V50)가 둘 다 동작한다.
         * 만료시각은 갱신이 성공해야 바뀌므로 주기 식별자로 쓰기에 알맞다.
         */
        fun renewalOrderId(subscription: UserSubscription): String =
            "renewal_${subscription.id}_${subscription.expiresAt?.epochSecond ?: 0L}"
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
    /** PG가 확정적으로 거절했다. 연속 실패 카운트에 들어간다. */
    data object Failed     : RenewResult
    /** PG 장애·불확정으로 판단을 미뤘다. 고객 잘못이 아니므로 카운트하지 않는다 (ADR-053). */
    data object Deferred   : RenewResult
    data object Downgraded : RenewResult
    data object Skipped    : RenewResult
}
