package com.monticker.api.subscription.application

import com.monticker.api.subscription.domain.*
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.SubscriptionPlanRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.subscription.infrastructure.pg.MockPgClient
import com.monticker.api.wallet.application.LedgerService
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Optional

class SubscriptionServiceTest {

    private val planRepo         = mockk<SubscriptionPlanRepository>()
    private val subscriptionRepo = mockk<UserSubscriptionRepository>()
    private val paymentRepo      = mockk<PaymentRecordRepository>()
    private val pgClient         = MockPgClient()          // 실제 Mock PG 사용
    private val ledgerService    = mockk<LedgerService>(relaxed = true)

    private val service = SubscriptionService(planRepo, subscriptionRepo, paymentRepo, pgClient, ledgerService)

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
