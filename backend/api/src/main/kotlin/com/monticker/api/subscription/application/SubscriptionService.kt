package com.monticker.api.subscription.application

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.common.metrics.PaymentMetrics
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
import java.util.UUID

@Service
class SubscriptionService(
    private val planRepo: SubscriptionPlanRepository,
    private val subscriptionRepo: UserSubscriptionRepository,
    private val paymentRepo: PaymentRecordRepository,
    private val pgClient: PgClient,
    private val ledgerService: LedgerService,
    private val billingKeyRepo: UserBillingKeyRepository,
    private val paymentMetrics: PaymentMetrics,
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
            paymentMetrics.declined()
            log.warn("결제 실패: userId={} plan={} reason={}", userId, planCode, result.failureReason)
            SubscribeResult.failure(planCode, result.failureReason ?: "결제 처리 중 오류가 발생했습니다.")
        }
    }

    /**
     * 일회성 결제 준비 — 프론트가 토스 SDK를 띄우기 **전에** 호출한다 (ADR-059).
     *
     * orderId와 결제 금액을 **서버가 정해서** PENDING 기록으로 남기고 돌려준다. 예전에는 둘 다
     * 프론트가 confirm 요청 바디에 실어 보냈고, 그래서 두 가지가 열려 있었다:
     *
     *  1. **금액을 클라이언트가 정했다.** 토스 confirm은 "보낸 금액이 실제 결제 금액과 같은가"만
     *     검증한다. 사용자가 SDK로 100원을 결제하고 `{amount:100, planCode:"PRO"}`로 confirm하면
     *     토스는 DONE을 주고 우리는 PRO를 활성화했다 — 플랜 가격은 기록에만 쓰였다.
     *  2. **orderId가 우리 것이 아니었다.** 멱등성의 열쇠를 클라이언트가 쥐고 있으면 DB 유니크
     *     인덱스가 막을 수 있는 것이 없다.
     *
     * 이제 orderId는 `payment_records.pg_order_id`의 유니크 인덱스(V50)가 지키는 서버 생성값이고,
     * 금액은 호출 시점의 플랜 가격으로 고정된다.
     */
    @Transactional
    fun preparePayment(userId: Long, planCode: PlanCode): PreparedPayment {
        val plan = planRepo.findByCode(planCode).orElseThrow {
            IllegalArgumentException("존재하지 않는 플랜: $planCode")
        }
        require(plan.price.toLong() > 0L) { "무료 플랜은 결제가 필요하지 않습니다." }

        val orderId = "sub_${userId}_${UUID.randomUUID()}"
        val record = paymentRepo.save(
            PaymentRecord(userId = userId, plan = plan, amount = plan.price, pgOrderId = orderId)
        )
        log.info("결제 준비: userId={} plan={} orderId={} amount={}", userId, planCode, orderId, plan.price)
        return PreparedPayment(orderId = orderId, amount = plan.price, planCode = plan.code, paymentId = record.id)
    }

    /** 준비된 결제를 orderId로 되찾는다. 남의 orderId로는 조회되지 않는다. */
    @Transactional(readOnly = true)
    fun findPreparedPayment(userId: Long, orderId: String): PaymentRecord? =
        paymentRepo.findByPgOrderId(orderId).orElse(null)?.takeIf { it.userId == userId }

    /**
     * 토스페이먼츠 confirm 플로우 전용(PaymentWebhookController.confirm 참고). 프론트가
     * 토스 SDK로 결제를 이미 완료했고, 컨트롤러가 tossPgClient.confirmPayment()로 그 결제를
     * 이미 확정한 뒤 호출한다.
     *
     * subscribe()처럼 pgClient.requestPayment()를 다시 호출하면 안 된다 —
     * TossPgClient.requestPayment()는 "웹훅 플로우를 쓰라"는 스텁이라 항상 실패를 반환하므로,
     * 여기서 다시 호출하면 방금 실제로 성공한 결제인데도 구독이 활성화되지 않고 PaymentRecord만
     * FAILED로 남는다 — 실제 코드에 있던 버그(고객은 결제됐는데 서비스는 활성화 안 됨).
     *
     * ADR-059 — preparePayment()가 만든 기록을 orderId로 찾아 그 위에 확정한다. 이미 SUCCESS면
     * 다시 활성화하지 않고 그 결과를 그대로 돌려준다(멱등 재생). 네트워크 재시도로 confirm이
     * 두 번 도착해도 결제 기록이 둘로 늘지 않는다.
     */
    @Transactional
    fun activateConfirmedSubscription(userId: Long, orderId: String, pgTransactionId: String): SubscribeResult {
        val record = paymentRepo.findByPgOrderId(orderId).orElseThrow {
            IllegalArgumentException("준비되지 않은 주문입니다: $orderId")
        }
        require(record.userId == userId) { "다른 사용자의 주문입니다." }

        if (record.status == PaymentStatus.SUCCESS) {
            log.info("confirm 재수신 — 이미 확정된 결제다. 그대로 돌려준다: orderId={}", orderId)
            return SubscribeResult.success(record.plan.code, paymentId = record.id)
        }
        return activatePaidPlan(userId, record.plan, record, pgTransactionId)
    }

    private fun activatePaidPlan(
        userId: Long,
        plan: SubscriptionPlan,
        record: PaymentRecord,
        pgTransactionId: String,
    ): SubscribeResult {
        // 구독 행이 없으면 먼저 만든다 — settlePayment 는 있는 구독만 연장한다.
        val subscription = getOrCreateSubscription(userId, plan)
        // 원장 금액은 기록의 금액이다(plan.price 가 아니라) — 준비 시점에 고정된 값이 과금의
        // 근거이고, 그 사이 플랜 가격이 바뀌었더라도 고객이 실제로 낸 금액은 기록 쪽이다 (ADR-059).
        settlePayment(record, pgTransactionId, subscription)
        log.info("구독 활성화: userId={} plan={} txId={}", userId, plan.code, pgTransactionId)
        return SubscribeResult.success(plan.code, paymentId = record.id)
    }

    /**
     * 구독 해지.
     *
     * 구독이 없을 때 `IllegalStateException`을 던지면 500이 된다 — GlobalExceptionHandler의
     * 키워드 휴리스틱에 "없습니다"는 없고 "없음"만 있다(BusinessRuleException 주석이 경고하는
     * 바로 그 함정). 해지 버튼을 두 번 누른 사용자가 500을 받는다. L-08 부하 시나리오에서
     * 반복마다 500이 하나씩 찍히는 것으로 드러났다.
     */
    @Transactional
    fun cancel(userId: Long) {
        val subscription = subscriptionRepo.findByUserId(userId).orElseThrow {
            BusinessRuleException("구독 정보가 없습니다.")
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
            settlePayment(record, result.pgTransactionId!!, subscription)
            log.info("구독 갱신 성공: userId={} plan={}", subscription.userId, plan.code)
            return RenewResult.Renewed
        }

        return when (result.failureKind ?: PaymentFailureKind.DECLINED) {
            // PG에 닿지도 못했다 — 청구되지 않았음이 확실하다. 기록을 PENDING으로 남겨두면
            // 다음 배치가 같은 orderId로 이어서 시도한다. 고객 잘못이 아니므로 카운트하지 않는다.
            PaymentFailureKind.UNAVAILABLE -> {
                paymentMetrics.unavailable()
                log.warn("갱신 보류 (PG 장애): userId={} orderId={} reason={}",
                    subscription.userId, orderId, result.failureReason)
                RenewResult.Deferred
            }
            // 청구됐는지 알 수 없다. 여기서 실패로 단정하고 다음 주기에 다시 긁으면 이중청구다.
            PaymentFailureKind.INDETERMINATE -> {
                paymentMetrics.indeterminate()
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
    private fun reconcilePending(record: PaymentRecord, orderId: String): RenewResult? =
        when (resolvePendingPayment(record)) {
            PaymentResolution.PAID        -> RenewResult.Renewed
            PaymentResolution.FAILED      -> RenewResult.Failed
            PaymentResolution.DEFERRED    -> RenewResult.Deferred
            PaymentResolution.NOT_CHARGED -> null    // 청구해도 안전하다
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
        paymentMetrics.declined()

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

    /**
     * 결제 성공을 확정하는 **유일한** 자리 — 기록·원장·구독 연장을 한 번에 한다 (ADR-059).
     *
     * 예전에는 세 경로(일회성 확정 / 갱신 성공 / 불확정 복구)가 각자 markSuccess + extend 를
     * 했고, **원장 기록은 일회성 경로에만 있었다.** 즉 정기결제가 성공해도
     * `SUBSCRIPTION_PAYMENT` 원장 행이 생기지 않았다 — 돈은 움직이고 회계는 비는 상태다.
     * `bench/consistency/verify.py` 의 "성공 결제 수 == 원장 행 수" 불변식이 바로 이걸 잡으려고
     * 있는 것이고, 갱신 배치가 한 번도 돌지 않았기 때문에 드러나지 않았을 뿐이다.
     */
    private fun settlePayment(
        record: PaymentRecord,
        pgTransactionId: String,
        /** 호출부가 이미 들고 있으면 넘긴다. 없으면 조회한다(적체 정리 경로). */
        subscription: UserSubscription? = null,
    ) {
        record.markSuccess(pgTransactionId)
        paymentRepo.save(record)
        paymentMetrics.success()
        ledgerService.recordSubscriptionPayment(
            record.userId, record.plan.code.name, record.amount, record.id,
        )
        val target = subscription ?: subscriptionRepo.findByUserId(record.userId).orElse(null)
        target?.let { extend(it, record.plan) }
    }

    /**
     * PENDING 으로 남은 결제 하나를 PG 에 물어 정리한다 (ADR-059).
     *
     * 갱신의 불확정 복구와 적체 청소 배치가 같은 코드를 쓴다 — 두 곳에 같은 판단을 적어두면
     * 반드시 어긋난다. 판단 규칙은 하나다: **PG 만이 진실을 알고, 모르겠으면 미룬다.**
     */
    @Transactional
    fun resolvePendingPayment(record: PaymentRecord): PaymentResolution {
        val orderId = record.pgOrderId ?: return PaymentResolution.NOT_CHARGED
        val status = pgClient.findPaymentByOrderId(orderId)

        if (status.lookupFailed) {
            // 조회조차 실패했다. "결제 안 됨"으로 읽으면 이중청구로 직행한다 — 판단을 미룬다.
            log.warn("PG 재조회 실패 — 판단 보류: orderId={}", orderId)
            return PaymentResolution.DEFERRED
        }
        if (!status.found) return PaymentResolution.NOT_CHARGED   // 권위 있는 "없음"

        if (status.status == "DONE") {
            log.warn("PENDING 이었으나 실제로는 청구되어 있었다 — 이중청구를 막았다: orderId={}", orderId)
            settlePayment(record, status.paymentKey ?: orderId)
            return PaymentResolution.PAID
        }

        // CANCELED / ABORTED / EXPIRED — PG가 확정적으로 실패라고 말한다.
        record.markFailed("PG 재조회 결과 ${status.status}")
        paymentRepo.save(record)
        return PaymentResolution.FAILED
    }

    /**
     * 권위 있는 "결제 없음" 상태로 너무 오래 남은 PENDING 을 닫는다 (ADR-059).
     * 열어둔 채 두면 적체가 영원히 늘고, 그 사용자의 연속 실패 판정도 흐려진다.
     */
    @Transactional
    fun expirePendingPayment(record: PaymentRecord, reason: String) {
        record.markFailed(reason)
        paymentRepo.save(record)
        log.warn("오래된 PENDING 결제를 실패로 닫았다: id={} orderId={}", record.id, record.pgOrderId)
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

/** preparePayment 가 프론트에 돌려주는 값 — 이 orderId·amount 로 토스 SDK 를 띄운다. */
data class PreparedPayment(
    val orderId: String,
    val amount: java.math.BigDecimal,
    val planCode: PlanCode,
    val paymentId: Long,
)

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

/** PENDING 결제 하나를 PG 에 물어본 결과 (ADR-059). */
enum class PaymentResolution {
    /** PG 가 DONE 이라고 답했다 — 청구됐다. 기록·원장·구독을 확정했다. */
    PAID,
    /** PG 가 확정적으로 실패(CANCELED/ABORTED/EXPIRED)라고 답했다. */
    FAILED,
    /** 조회 자체가 실패했다. 아무것도 단정하지 않는다. */
    DEFERRED,
    /** PG 에 그 orderId 로 된 결제가 없다. 청구해도 안전하다. */
    NOT_CHARGED,
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
