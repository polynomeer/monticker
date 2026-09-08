package com.monticker.api.subscription.application

import com.monticker.api.subscription.domain.*
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.SubscriptionPlanRepository
import com.monticker.api.subscription.infrastructure.UserBillingKeyRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.subscription.infrastructure.pg.MockPgClient
import com.monticker.api.subscription.infrastructure.pg.PaymentResult
import com.monticker.api.wallet.application.LedgerService
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageImpl
import java.math.BigDecimal
import java.util.Optional

class SubscriptionServiceTest {

    private val planRepo         = mockk<SubscriptionPlanRepository>()
    private val subscriptionRepo = mockk<UserSubscriptionRepository>()
    private val paymentRepo      = mockk<PaymentRecordRepository>()
    private val pgClient         = MockPgClient()          // 실제 Mock PG 사용
    private val ledgerService    = mockk<LedgerService>(relaxed = true)
    private val billingKeyRepo   = mockk<UserBillingKeyRepository>()

    private val service = SubscriptionService(planRepo, subscriptionRepo, paymentRepo, pgClient, ledgerService, billingKeyRepo)

    // ── subscribe ─────────────────────────────────────────────────────────────

    @Test
    fun `FREE 플랜 구독 시 PG 결제 없이 구독 활성화된다`() {
        val freePlan = makePlan(PlanCode.FREE, price = BigDecimal.ZERO)
        val sub      = makeSubscription(freePlan)

        every { planRepo.findByCode(PlanCode.FREE) } returns Optional.of(freePlan)
        every { subscriptionRepo.findByUserId(1L) }  returns Optional.of(sub)
        every { subscriptionRepo.save(any()) }        returns sub

        val result = service.subscribe(userId = 1L, planCode = PlanCode.FREE)

        assertThat(result.success).isTrue()
        assertThat(result.paymentId).isNull()
        verify(exactly = 0) { paymentRepo.save(any()) }
        verify(exactly = 0) { ledgerService.recordSubscriptionPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `PRO 플랜 구독 시 PG 결제 성공 후 구독 활성화된다`() {
        val proPlan = makePlan(PlanCode.PRO, price = BigDecimal("9900"))
        val record  = makePaymentRecord(proPlan)
        val sub     = makeSubscription(proPlan)

        every { planRepo.findByCode(PlanCode.PRO) }  returns Optional.of(proPlan)
        every { subscriptionRepo.findByUserId(1L) }  returns Optional.of(sub)
        every { paymentRepo.save(any()) }             returns record
        every { subscriptionRepo.save(any()) }        returns sub

        val result = service.subscribe(userId = 1L, planCode = PlanCode.PRO)

        assertThat(result.success).isTrue()
        verify { ledgerService.recordSubscriptionPayment(1L, "PRO", BigDecimal("9900"), any()) }
    }

    @Test
    fun `존재하지 않는 플랜 요청 시 예외가 발생한다`() {
        every { planRepo.findByCode(PlanCode.QUANT) } returns Optional.empty()

        assertThrows<IllegalArgumentException> {
            service.subscribe(userId = 1L, planCode = PlanCode.QUANT)
        }
    }

    // ── activateConfirmedSubscription ────────────────────────────────────────
    // 실제로 있던 버그: PaymentWebhookController가 토스 confirm으로 결제를 이미 성공시킨
    // 뒤 subscribe()를 호출했는데, subscribe()가 내부적으로 pgClient.requestPayment()를 또
    // 호출했다. TossPgClient.requestPayment()는 웹훅 플로우를 쓰라는 스텁이라 항상 실패를
    // 반환하므로, 실제로 결제된 고객의 구독이 활성화되지 않고 PaymentRecord만 FAILED로
    // 남았다. activateConfirmedSubscription()은 pgClient를 아예 다시 호출하지 않아야 한다.

    @Test
    fun `activateConfirmedSubscription은 pgClient를 다시 호출하지 않고 바로 구독을 활성화한다`() {
        val proPlan = makePlan(PlanCode.PRO, price = BigDecimal("9900"))
        val record  = makePaymentRecord(proPlan)
        val sub     = makeSubscription(proPlan)
        val spyPgClient = spyk(pgClient)
        val serviceWithSpy = SubscriptionService(planRepo, subscriptionRepo, paymentRepo, spyPgClient, ledgerService, billingKeyRepo)

        every { planRepo.findByCode(PlanCode.PRO) } returns Optional.of(proPlan)
        every { subscriptionRepo.findByUserId(1L) } returns Optional.of(sub)
        every { paymentRepo.save(any()) }            returns record
        every { subscriptionRepo.save(any()) }        returns sub

        val result = serviceWithSpy.activateConfirmedSubscription(
            userId = 1L, planCode = PlanCode.PRO, pgTransactionId = "toss_already_confirmed_tx",
        )

        assertThat(result.success).isTrue()
        assertThat(result.paymentId).isEqualTo(record.id)
        verify(exactly = 0) { spyPgClient.requestPayment(any()) }
        verify { ledgerService.recordSubscriptionPayment(1L, "PRO", BigDecimal("9900"), any()) }
    }

    // ── renewSubscription ────────────────────────────────────────────────────
    // 실제로 있던 (기능이 아예 없던) 문제: 정기결제는 confirm과 다른 별도 API(빌링키)가
    // 필요한데, renewSubscription()은 pgClient.requestPayment()를 호출하고 있었다 —
    // TossPgClient에서는 그게 항상 실패하는 스텁이라 실제 운영에서는 자동 갱신이 절대
    // 성공할 수 없었다. 이제는 저장된 빌링키로 pgClient.chargeBilling()을 호출한다.

    @Test
    fun `renewSubscription은 등록된 빌링키가 없으면 결제를 시도조차 하지 않고 실패 처리한다`() {
        val proPlan = makePlan(PlanCode.PRO, price = BigDecimal("9900"))
        val sub     = makeSubscription(proPlan)
        val record  = makePaymentRecord(proPlan).also { it.status = PaymentStatus.PENDING }

        every { billingKeyRepo.findByUserId(1L) } returns Optional.empty()
        every { paymentRepo.save(any()) }          returns record
        every {
            paymentRepo.findAllByUserIdOrderByCreatedAtDesc(1L, Pageable.ofSize(3))
        } returns PageImpl(listOf(record))

        val result = service.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Failed)
        assertThat(record.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(record.failureReason).contains("등록된 자동결제 카드가 없습니다")
    }

    @Test
    fun `renewSubscription은 등록된 빌링키로 chargeBilling을 호출해 자동 갱신에 성공한다`() {
        val proPlan = makePlan(PlanCode.PRO, price = BigDecimal("9900"))
        val sub     = makeSubscription(proPlan)
        val record  = makePaymentRecord(proPlan).also { it.status = PaymentStatus.PENDING }
        val billingKey = UserBillingKey(
            id = 1L, userId = 1L, customerKey = "cust_1", billingKeyValue = "billing_key_1",
        )
        val spyPgClient = spyk(pgClient)
        val serviceWithSpy = SubscriptionService(planRepo, subscriptionRepo, paymentRepo, spyPgClient, ledgerService, billingKeyRepo)

        every { billingKeyRepo.findByUserId(1L) } returns Optional.of(billingKey)
        every { paymentRepo.save(any()) }          returns record
        every { subscriptionRepo.save(any()) }     returns sub

        val result = serviceWithSpy.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Renewed)
        verify {
            spyPgClient.chargeBilling(
                billingKey = "billing_key_1", customerKey = "cust_1", amount = BigDecimal("9900"),
                orderId = any(), orderName = any(),
            )
        }
        verify(exactly = 0) { spyPgClient.requestPayment(any()) }
    }

    @Test
    fun `renewSubscription은 3회 연속 실패하면 FREE로 다운그레이드한다`() {
        val proPlan  = makePlan(PlanCode.PRO, price = BigDecimal("9900"))
        val freePlan = makePlan(PlanCode.FREE, price = BigDecimal.ZERO)
        val sub      = makeSubscription(proPlan)
        val record   = makePaymentRecord(proPlan).also { it.status = PaymentStatus.PENDING }
        val pastFailures = listOf(
            makePaymentRecord(proPlan).also { it.status = PaymentStatus.FAILED },
            makePaymentRecord(proPlan).also { it.status = PaymentStatus.FAILED },
        )

        every { billingKeyRepo.findByUserId(1L) }  returns Optional.empty()
        every { paymentRepo.save(any()) }           returns record
        every {
            paymentRepo.findAllByUserIdOrderByCreatedAtDesc(1L, Pageable.ofSize(3))
        } returns PageImpl(pastFailures + record)
        every { planRepo.findByCode(PlanCode.FREE) } returns Optional.of(freePlan)
        every { subscriptionRepo.save(any()) }        returns sub

        val result = service.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Downgraded)
        assertThat(sub.plan.code).isEqualTo(PlanCode.FREE)
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    fun `활성 구독 해지 시 CANCELLED 상태로 전환된다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan, status = SubscriptionStatus.ACTIVE)

        every { subscriptionRepo.findByUserId(1L) } returns Optional.of(sub)
        every { subscriptionRepo.save(any()) }       returns sub

        service.cancel(userId = 1L)

        assertThat(sub.status).isEqualTo(SubscriptionStatus.CANCELLED)
        assertThat(sub.cancelledAt).isNotNull()
    }

    @Test
    fun `이미 해지된 구독 재해지 시 예외가 발생한다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan, status = SubscriptionStatus.CANCELLED)

        every { subscriptionRepo.findByUserId(1L) } returns Optional.of(sub)

        assertThrows<IllegalArgumentException> {
            service.cancel(userId = 1L)
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makePlan(code: PlanCode, price: BigDecimal) = SubscriptionPlan(
        id = code.ordinal.toLong() + 1,
        code = code,
        name = code.name,
        price = price,
    )

    private fun makeSubscription(plan: SubscriptionPlan, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
        UserSubscription(id = 1L, userId = 1L, plan = plan, status = status)

    private fun makePaymentRecord(plan: SubscriptionPlan) =
        PaymentRecord(id = 99L, userId = 1L, plan = plan, amount = plan.price, status = PaymentStatus.SUCCESS)
}
