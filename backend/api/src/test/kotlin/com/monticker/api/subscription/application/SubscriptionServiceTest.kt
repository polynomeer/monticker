package com.monticker.api.subscription.application

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.subscription.domain.*
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.SubscriptionPlanRepository
import com.monticker.api.subscription.infrastructure.UserBillingKeyRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.subscription.infrastructure.pg.BillingKeyResult
import com.monticker.api.subscription.infrastructure.pg.MockPgClient
import com.monticker.api.subscription.infrastructure.pg.PaymentFailureKind
import com.monticker.api.subscription.infrastructure.pg.PaymentRequest
import com.monticker.api.subscription.infrastructure.pg.PaymentResult
import com.monticker.api.subscription.infrastructure.pg.PaymentStatusResult
import com.monticker.api.subscription.infrastructure.pg.PgClient
import com.monticker.api.subscription.infrastructure.pg.RefundResult
import com.monticker.api.wallet.application.LedgerService
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageImpl
import java.math.BigDecimal
import java.time.Instant
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
        val record  = makePaymentRecord(proPlan).also { it.status = PaymentStatus.PENDING }
        val sub     = makeSubscription(proPlan)
        val spyPgClient = spyk(pgClient)
        val serviceWithSpy = SubscriptionService(planRepo, subscriptionRepo, paymentRepo, spyPgClient, ledgerService, billingKeyRepo)

        every { paymentRepo.findByPgOrderId("sub_1_abc") } returns Optional.of(record)
        every { subscriptionRepo.findByUserId(1L) }        returns Optional.of(sub)
        every { paymentRepo.save(any()) }                   returns record
        every { subscriptionRepo.save(any()) }               returns sub

        val result = serviceWithSpy.activateConfirmedSubscription(
            userId = 1L, orderId = "sub_1_abc", pgTransactionId = "toss_already_confirmed_tx",
        )

        assertThat(result.success).isTrue()
        assertThat(result.paymentId).isEqualTo(record.id)
        verify(exactly = 0) { spyPgClient.requestPayment(any()) }
        verify { ledgerService.recordSubscriptionPayment(1L, "PRO", BigDecimal("9900"), any()) }
    }

    // ── ADR-059: 일회성 결제도 서버가 orderId·금액을 쥔다 ─────────────────────

    @Test
    fun `preparePayment는 서버 생성 orderId와 플랜 가격을 PENDING 기록으로 남긴다`() {
        val proPlan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val saved = slot<PaymentRecord>()
        every { planRepo.findByCode(PlanCode.PRO) } returns Optional.of(proPlan)
        every { paymentRepo.save(capture(saved)) }   answers { saved.captured }

        val prepared = service.preparePayment(userId = 1L, planCode = PlanCode.PRO)

        assertThat(prepared.orderId).startsWith("sub_1_")
        assertThat(prepared.amount).isEqualByComparingTo(BigDecimal("9900"))
        assertThat(saved.captured.pgOrderId).isEqualTo(prepared.orderId)
        assertThat(saved.captured.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `preparePayment는 같은 사용자가 두 번 호출해도 서로 다른 orderId를 준다`() {
        // 멱등 키는 요청마다 달라야 한다 — 같으면 두 번째 결제가 DB 유니크에 막힌다.
        val proPlan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        every { planRepo.findByCode(PlanCode.PRO) } returns Optional.of(proPlan)
        every { paymentRepo.save(any()) }            answers { firstArg() }

        val a = service.preparePayment(1L, PlanCode.PRO)
        val b = service.preparePayment(1L, PlanCode.PRO)

        assertThat(a.orderId).isNotEqualTo(b.orderId)
    }

    @Test
    fun `무료 플랜은 결제를 준비하지 않는다`() {
        val freePlan = makePlan(PlanCode.FREE, BigDecimal.ZERO)
        every { planRepo.findByCode(PlanCode.FREE) } returns Optional.of(freePlan)

        assertThrows<IllegalArgumentException> { service.preparePayment(1L, PlanCode.FREE) }
        verify(exactly = 0) { paymentRepo.save(any()) }
    }

    @Test
    fun `이미 확정된 주문을 다시 활성화하면 구독을 건드리지 않고 그 결과를 돌려준다`() {
        val proPlan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val paid    = makePaymentRecord(proPlan)   // status = SUCCESS
        every { paymentRepo.findByPgOrderId("sub_1_abc") } returns Optional.of(paid)

        val result = service.activateConfirmedSubscription(1L, "sub_1_abc", "tx_second")

        assertThat(result.success).isTrue()
        assertThat(result.paymentId).isEqualTo(paid.id)
        assertThat(paid.pgTransactionId).isNull()   // 첫 확정의 txId 를 덮어쓰지 않는다
        verify(exactly = 0) { subscriptionRepo.save(any()) }
        verify(exactly = 0) { ledgerService.recordSubscriptionPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `남의 orderId로는 확정할 수 없다`() {
        val proPlan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val other   = makePaymentRecord(proPlan).also { it.status = PaymentStatus.PENDING }
        every { paymentRepo.findByPgOrderId("sub_1_abc") } returns Optional.of(other)   // userId = 1

        assertThrows<IllegalArgumentException> {
            service.activateConfirmedSubscription(userId = 999L, orderId = "sub_1_abc", pgTransactionId = "tx")
        }
    }

    @Test
    fun `findPreparedPayment는 다른 사용자의 주문을 돌려주지 않는다`() {
        val proPlan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        every { paymentRepo.findByPgOrderId("sub_1_abc") } returns Optional.of(makePaymentRecord(proPlan))

        assertThat(service.findPreparedPayment(1L, "sub_1_abc")).isNotNull()
        assertThat(service.findPreparedPayment(999L, "sub_1_abc")).isNull()
    }

    // ── renewSubscription ────────────────────────────────────────────────────
    // 실제로 있던 (기능이 아예 없던) 문제: 정기결제는 confirm과 다른 별도 API(빌링키)가
    // 필요한데, renewSubscription()은 pgClient.requestPayment()를 호출하고 있었다 —
    // TossPgClient에서는 그게 항상 실패하는 스텁이라 실제 운영에서는 자동 갱신이 절대
    // 성공할 수 없었다. 이제는 저장된 빌링키로 pgClient.chargeBilling()을 호출한다.

    /** 갱신 경로가 매번 세우는 스텁 — orderId 조회는 비어 있고, 연속 실패는 0이 기본이다. */
    private fun stubRenewal(record: PaymentRecord, consecutiveFailures: Long = 0L) {
        every { paymentRepo.findByPgOrderId(any()) } returns Optional.empty()
        every { paymentRepo.save(any()) } returns record
        every {
            paymentRepo.findFirstByUserIdAndStatusOrderByCreatedAtDesc(1L, PaymentStatus.SUCCESS)
        } returns Optional.empty()
        every {
            paymentRepo.countByUserIdAndStatusAndCreatedAtAfter(1L, PaymentStatus.FAILED, Instant.EPOCH)
        } returns consecutiveFailures
    }

    @Test
    fun `renewSubscription은 등록된 빌링키가 없으면 결제를 시도조차 하지 않고 실패 처리한다`() {
        val proPlan = makePlan(PlanCode.PRO, price = BigDecimal("9900"))
        val sub     = makeSubscription(proPlan)
        val record  = makePaymentRecord(proPlan).also { it.status = PaymentStatus.PENDING }

        every { billingKeyRepo.findByUserId(1L) } returns Optional.empty()
        stubRenewal(record)

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
        stubRenewal(record)
        every { subscriptionRepo.save(any()) } returns sub

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

        every { billingKeyRepo.findByUserId(1L) } returns Optional.empty()
        stubRenewal(record, consecutiveFailures = 3L)
        every { planRepo.findByCode(PlanCode.FREE) } returns Optional.of(freePlan)
        every { subscriptionRepo.save(any()) }        returns sub

        val result = service.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Downgraded)
        assertThat(sub.plan.code).isEqualTo(PlanCode.FREE)
    }

    // ── 정기결제 멱등성·장애 (ADR-053) ────────────────────────────────────────
    //
    // 주문 체결 쪽은 멱등 키 + 유니크 인덱스 + 동시성 테스트까지 갖춰져 있는데 결제 쪽은
    // 그 어느 것도 없었다. 아래는 "같은 주기를 두 번 긁지 않는다"와 "PG 장애를 카드 거절로
    // 읽지 않는다" 두 가지를 본다 — 둘 다 실패하면 곧바로 돈 문제가 된다.

    @Test
    fun `같은 청구주기의 orderId는 재시도해도 동일하다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan).also { it.expiresAt = Instant.parse("2026-10-01T00:00:00Z") }

        val first  = SubscriptionService.renewalOrderId(sub)
        val second = SubscriptionService.renewalOrderId(sub)

        assertThat(first).isEqualTo(second)
        // 예전 구현은 System.currentTimeMillis()를 넣어 매번 달랐다 — 토스의 orderId 중복
        // 방어가 통째로 무력해지고 배치 재실행이 곧 이중청구였다.
        assertThat(first).doesNotContain(System.currentTimeMillis().toString().take(8))
    }

    @Test
    fun `갱신이 성공해 만료일이 바뀌면 다음 주기의 orderId도 바뀐다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan).also { it.expiresAt = Instant.parse("2026-10-01T00:00:00Z") }
        val thisCycle = SubscriptionService.renewalOrderId(sub)

        sub.expiresAt = Instant.parse("2026-11-01T00:00:00Z")

        assertThat(SubscriptionService.renewalOrderId(sub)).isNotEqualTo(thisCycle)
    }

    @Test
    fun `이미 성공한 주기를 다시 갱신하면 청구하지 않고 연장만 한다`() {
        // 청구는 성공했는데 구독 연장 직전에 프로세스가 죽은 경우. 배치가 다시 돌면
        // 같은 orderId의 SUCCESS 기록을 먼저 만나야 하고, 카드를 다시 긁으면 안 된다.
        val plan   = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub    = makeSubscription(plan)
        val paid   = makePaymentRecord(plan).also { it.status = PaymentStatus.SUCCESS }
        val spyPg  = spyk(pgClient)
        val svc    = SubscriptionService(planRepo, subscriptionRepo, paymentRepo, spyPg, ledgerService, billingKeyRepo)

        every { paymentRepo.findByPgOrderId(any()) } returns Optional.of(paid)
        every { subscriptionRepo.save(any()) }        returns sub

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Renewed)
        assertThat(sub.expiresAt).isAfter(Instant.now())
        verify(exactly = 0) { spyPg.chargeBilling(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `PG 장애는 결제 거절이 아니다 — 다운그레이드 카운트에 들어가지 않는다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(
            charge = PaymentResult(false, failureReason = "CB OPEN", failureKind = PaymentFailureKind.UNAVAILABLE)
        ))

        every { billingKeyRepo.findByUserId(1L) } returns Optional.of(billingKey())
        stubRenewal(rec, consecutiveFailures = 2L)   // 한 번만 더 실패하면 강등되는 상태

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Deferred)
        assertThat(rec.status).isEqualTo(PaymentStatus.PENDING)   // FAILED로 굳히지 않는다
        assertThat(sub.plan.code).isEqualTo(PlanCode.PRO)         // 강등되지 않았다
    }

    @Test
    fun `응답을 못 받았는데 실제로는 청구돼 있었다면 성공으로 정리하고 다시 긁지 않는다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val fake = FakePgClient(
            charge = PaymentResult(false, failureReason = "read timeout", failureKind = PaymentFailureKind.INDETERMINATE),
            lookup = PaymentStatusResult(found = true, status = "DONE", paymentKey = "pay_abc"),
        )
        val svc = serviceWith(fake)

        every { billingKeyRepo.findByUserId(1L) } returns Optional.of(billingKey())
        every { subscriptionRepo.findByUserId(1L) } returns Optional.of(sub)
        every { subscriptionRepo.save(any()) }      returns sub
        stubRenewal(rec)

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Renewed)
        assertThat(rec.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(rec.pgTransactionId).isEqualTo("pay_abc")
        assertThat(fake.chargeCalls).isEqualTo(1)        // 타임아웃 난 그 한 번뿐 — 재청구 없음
    }

    @Test
    fun `응답을 못 받았고 PG 조회도 실패하면 판단을 미룬다`() {
        // 여기서 "결제 안 됐다"고 단정하면 다음 배치가 다시 긁어 이중청구가 된다.
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(
            charge = PaymentResult(false, failureKind = PaymentFailureKind.INDETERMINATE),
            lookup = PaymentStatusResult(found = false, lookupFailed = true),
        ))

        every { billingKeyRepo.findByUserId(1L) } returns Optional.of(billingKey())
        stubRenewal(rec, consecutiveFailures = 2L)

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Deferred)
        assertThat(rec.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(sub.plan.code).isEqualTo(PlanCode.PRO)
    }

    @Test
    fun `지난 시도가 불확정으로 남아 있으면 긁기 전에 PG에 먼저 물어본다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val pending = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val fake = FakePgClient(lookup = PaymentStatusResult(found = true, status = "DONE", paymentKey = "pay_xyz"))
        val svc  = serviceWith(fake)

        every { billingKeyRepo.findByUserId(1L) }   returns Optional.of(billingKey())
        every { paymentRepo.findByPgOrderId(any()) } returns Optional.of(pending)
        every { paymentRepo.save(any()) }            returns pending
        every { subscriptionRepo.findByUserId(1L) }  returns Optional.of(sub)
        every { subscriptionRepo.save(any()) }       returns sub

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Renewed)
        assertThat(fake.chargeCalls).isZero()        // 단 한 번도 긁지 않았다
        assertThat(fake.lookupCalls).isEqualTo(1)
    }

    @Test
    fun `PG가 확정적으로 결제 없음이라고 답하면 정상적으로 청구한다`() {
        // 불확정 복구가 과잉동작해서 정상 갱신까지 막아버리면 그것대로 사고다.
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val pending = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val fake = FakePgClient(
            charge = PaymentResult(true, pgTransactionId = "pay_new"),
            lookup = PaymentStatusResult(found = false),      // 권위 있는 "없음"
        )
        val svc = serviceWith(fake)

        every { billingKeyRepo.findByUserId(1L) }   returns Optional.of(billingKey())
        every { paymentRepo.findByPgOrderId(any()) } returns Optional.of(pending)
        every { paymentRepo.save(any()) }            returns pending
        every { subscriptionRepo.save(any()) }       returns sub

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Renewed)
        assertThat(fake.chargeCalls).isEqualTo(1)
        assertThat(pending.pgTransactionId).isEqualTo("pay_new")
    }

    @Test
    fun `카드 거절은 그대로 실패로 세고 3회째에 강등한다`() {
        val plan     = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val freePlan = makePlan(PlanCode.FREE, BigDecimal.ZERO)
        val sub      = makeSubscription(plan)
        val rec      = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc      = serviceWith(FakePgClient(
            charge = PaymentResult(false, failureReason = "잔액 부족", failureKind = PaymentFailureKind.DECLINED)
        ))

        every { billingKeyRepo.findByUserId(1L) }    returns Optional.of(billingKey())
        stubRenewal(rec, consecutiveFailures = 3L)
        every { planRepo.findByCode(PlanCode.FREE) }  returns Optional.of(freePlan)
        every { subscriptionRepo.save(any()) }        returns sub

        val result = svc.renewSubscription(sub)

        assertThat(result).isEqualTo(RenewResult.Downgraded)
        assertThat(rec.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(sub.plan.code).isEqualTo(PlanCode.FREE)
    }

    // ── ADR-059: PENDING 적체 정리와 원장 누락 ────────────────────────────────

    @Test
    fun `갱신 성공도 원장에 기록된다`() {
        // 오래된 결함: 성공 경로가 세 곳(일회성 확정 / 갱신 성공 / 불확정 복구)에 흩어져 있었고
        // **원장 기록은 일회성 경로에만** 있었다. 즉 정기결제가 성공해도 SUBSCRIPTION_PAYMENT
        // 원장 행이 생기지 않았다 — 돈은 움직이고 회계는 비는 상태.
        // verify.py 의 "성공 결제 수 == 원장 행 수" 불변식이 이걸 잡으려고 있는 것이고,
        // 갱신 배치가 한 번도 돌지 않았기 때문에 드러나지 않았을 뿐이다.
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(charge = PaymentResult(true, pgTransactionId = "tx_renew")))

        every { billingKeyRepo.findByUserId(1L) }   returns Optional.of(billingKey())
        every { subscriptionRepo.findByUserId(1L) } returns Optional.of(sub)
        every { subscriptionRepo.save(any()) }      returns sub
        stubRenewal(rec)

        assertThat(svc.renewSubscription(sub)).isEqualTo(RenewResult.Renewed)

        verify { ledgerService.recordSubscriptionPayment(1L, "PRO", BigDecimal("9900"), rec.id) }
    }

    @Test
    fun `불확정 복구로 성공 처리된 결제도 원장에 기록된다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val sub  = makeSubscription(plan)
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(
            lookup = PaymentStatusResult(found = true, status = "DONE", paymentKey = "pay_x")))

        every { paymentRepo.save(any()) }            returns rec
        every { subscriptionRepo.findByUserId(1L) }  returns Optional.of(sub)
        every { subscriptionRepo.save(any()) }       returns sub

        assertThat(svc.resolvePendingPayment(rec)).isEqualTo(PaymentResolution.PAID)

        assertThat(rec.status).isEqualTo(PaymentStatus.SUCCESS)
        verify { ledgerService.recordSubscriptionPayment(1L, "PRO", BigDecimal("9900"), rec.id) }
    }

    @Test
    fun `PG가 결제 없음이라 하면 아무것도 확정하지 않는다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(lookup = PaymentStatusResult(found = false)))

        assertThat(svc.resolvePendingPayment(rec)).isEqualTo(PaymentResolution.NOT_CHARGED)

        assertThat(rec.status).isEqualTo(PaymentStatus.PENDING)   // 닫지 않는다 — 다시 긁어도 안전
        verify(exactly = 0) { ledgerService.recordSubscriptionPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `PG 조회가 실패하면 판단을 미룬다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(lookup = PaymentStatusResult(found = false, lookupFailed = true)))

        assertThat(svc.resolvePendingPayment(rec)).isEqualTo(PaymentResolution.DEFERRED)
        assertThat(rec.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `PG가 취소라고 답하면 실패로 닫는다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        val svc  = serviceWith(FakePgClient(
            lookup = PaymentStatusResult(found = true, status = "CANCELED")))

        every { paymentRepo.save(any()) } returns rec

        assertThat(svc.resolvePendingPayment(rec)).isEqualTo(PaymentResolution.FAILED)
        assertThat(rec.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(rec.failureReason).contains("CANCELED")
    }

    @Test
    fun `orderId가 없는 결제는 PG에 되물을 수 없으므로 건드리지 않는다`() {
        // confirm 플로우 이전의 구식 기록. 열쇠가 없으면 판단할 방법도 없다.
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val rec  = PaymentRecord(id = 1L, userId = 1L, plan = plan, amount = plan.price,
                                 pgOrderId = null, status = PaymentStatus.PENDING)
        val fake = FakePgClient()
        val svc  = serviceWith(fake)

        assertThat(svc.resolvePendingPayment(rec)).isEqualTo(PaymentResolution.NOT_CHARGED)
        assertThat(fake.lookupCalls).isZero()
    }

    @Test
    fun `오래된 PENDING은 실패로 닫힌다`() {
        val plan = makePlan(PlanCode.PRO, BigDecimal("9900"))
        val rec  = makePaymentRecord(plan).also { it.status = PaymentStatus.PENDING }
        every { paymentRepo.save(any()) } returns rec

        service.expirePendingPayment(rec, "72시간 동안 PG에 결제 기록이 없었습니다.")

        assertThat(rec.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(rec.failureReason).contains("72시간")
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
    fun `구독이 없는데 해지하면 500이 아니라 409가 되는 예외를 던진다`() {
        // 해지 버튼을 두 번 누른 사용자가 500을 받고 있었다. GlobalExceptionHandler의
        // 키워드 휴리스틱에 "없습니다"는 없고 "없음"만 있어서 IllegalStateException이
        // 그대로 500으로 샜다 — L-08 부하 시나리오에서 반복마다 하나씩 찍혀 드러났다.
        every { subscriptionRepo.findByUserId(1L) } returns Optional.empty()

        assertThrows<BusinessRuleException> { service.cancel(userId = 1L) }
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

    /** pgOrderId 를 꼭 준다 — 그게 PG 에 되물을 유일한 열쇠이고, 없으면 판단 자체가 불가능하다. */
    private fun makePaymentRecord(plan: SubscriptionPlan) =
        PaymentRecord(id = 99L, userId = 1L, plan = plan, amount = plan.price,
                      pgOrderId = "sub_1_abc", status = PaymentStatus.SUCCESS)

    private fun billingKey() =
        UserBillingKey(id = 1L, userId = 1L, customerKey = "cust_1", billingKeyValue = "billing_key_1")

    private fun serviceWith(pg: PgClient) =
        SubscriptionService(planRepo, subscriptionRepo, paymentRepo, pg, ledgerService, billingKeyRepo)

    /**
     * 청구 결과와 재조회 결과를 미리 정해두는 PG. mockk 대신 쓰는 이유는 호출 횟수가
     * 이 테스트들의 본체이기 때문이다 — "몇 번 긁었나"를 세는 게 곧 이중청구 검증이다.
     */
    private class FakePgClient(
        private val charge: PaymentResult = PaymentResult(true, pgTransactionId = "pay_ok"),
        private val lookup: PaymentStatusResult = PaymentStatusResult(found = false),
    ) : PgClient {
        var chargeCalls = 0; private set
        var lookupCalls = 0; private set

        override fun chargeBilling(
            billingKey: String, customerKey: String, amount: BigDecimal, orderId: String, orderName: String,
        ): PaymentResult { chargeCalls++; return charge }

        override fun findPaymentByOrderId(orderId: String): PaymentStatusResult { lookupCalls++; return lookup }

        override fun requestPayment(request: PaymentRequest) = PaymentResult(false)
        override fun requestRefund(pgTransactionId: String, amount: BigDecimal) = RefundResult(true)
        override fun getPaymentStatus(paymentKey: String) = lookup
        override fun issueBillingKey(authKey: String, customerKey: String) = BillingKeyResult(true, "bk")
    }
}
