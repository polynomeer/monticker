package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderResolution
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageBalance
import com.monticker.api.brokerage.infrastructure.BrokerageCancelResult
import com.monticker.api.brokerage.infrastructure.BrokerageClient
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRepository
import com.monticker.api.brokerage.infrastructure.BrokerOrderSnapshot
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.BrokerageOrderResult
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.brokerage.infrastructure.BrokerageToken
import com.monticker.api.brokerage.infrastructure.MockBrokerageClient
import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.common.exception.ReconnectRequiredException
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.wallet.application.LedgerService
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class BrokerageServiceTest {

    private val jdbc           = mockk<JdbcTemplate>(relaxed = true)
    private val mockClient     = MockBrokerageClient(jdbc)
    private val clientRegistry = BrokerageClientRegistry(BrokerageProvider.entries.associateWith { mockClient })
    private val accountRepo    = mockk<BrokerageAccountRepository>()
    private val orderRepo      = mockk<BrokerageOrderRepository>()
    private val settlementRepo = mockk<BrokerageSettlementRepository>()
    private val ledgerService  = mockk<LedgerService>(relaxed = true)
    private val riskChecker    = mockk<RiskCheckerService>()

    private val txManager      = mockk<PlatformTransactionManager>(relaxed = true)
    private val meterRegistry  = SimpleMeterRegistry()
    private val haltService    = mockk<TradingHaltService> { every { findActive(any(), any()) } returns null }
    private val pendingBuyQuery = mockk<PendingBuyQuery> { every { pendingBuys(any(), any()) } returns emptyMap() }

    init {
        // ADR-058 — 리스크 게이트 직전에 같은 종목의 진행 중 매수를 갱신한다. 기본은 없음.
        every { orderRepo.findAllByAccountIdAndStockIdAndSideAndStatusAndSubmittedAtAfter(any(), any(), any(), any(), any()) } returns emptyList()
    }

    private val service = BrokerageService(clientRegistry, accountRepo, orderRepo, settlementRepo, ledgerService, riskChecker, jdbc, txManager, meterRegistry, haltService, pendingBuyQuery)

    private val approvedRisk = RiskCheckResult(approved = true, blockedBy = null, severity = "APPROVED", checks = emptyList())

    /**
     * resolveStockId()가 실제로 종목을 찾은 것처럼 만들어 리스크 게이트가 항상 평가되게
     * 하고, buildPortfolioSnapshot()의 최근 주문 수 조회도 기본값(0건)으로 응답시킨다 —
     * 안 해두면 relaxed 목이 Long으로 캐스팅 불가능한 값을 돌려줘서 ClassCastException이 난다.
     */
    private fun stubStockLookup(stockId: Long = 1L) {
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, any()) } returns stockId
        every { jdbc.queryForObject(any<String>(), eq(Long::class.java), any(), any()) } returns 0L
    }

    /**
     * ADR-056 — 제출은 tx1(의도 저장) → 증권사 → tx2(행을 락 걸고 다시 읽어 결과 기록)다. 저장된 엔티티를 그대로
     * 돌려주는 가짜 저장소로 두 단계가 같은 행을 보게 한다.
     */
    private fun stubSubmitPersistence(account: BrokerageAccount, orderSlot: CapturingSlot<BrokerageOrder>) {
        every { orderRepo.findAllByUserIdAndSymbolAndStatusIn(any(), any(), any()) } returns emptyList()
        every { orderRepo.save(capture(orderSlot)) } answers { firstArg() }
        every { orderRepo.findWithLockById(any()) } answers { orderSlot.captured }
        every { accountRepo.findById(account.id) } returns Optional.of(account)
    }

    // ── connect ───────────────────────────────────────────────────────────────

    @Test
    fun `계좌 연동 시 Mock 토큰이 발급되고 저장된다`() {
        val accountSlot = slot<BrokerageAccount>()
        every { accountRepo.findByUserIdAndProviderAndAccountNumber(1L, BrokerageProvider.KIS, "12345678") } returns Optional.empty()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.empty()
        every { accountRepo.save(capture(accountSlot)) }      returns makeAccount()

        service.connect(userId = 1L, provider = BrokerageProvider.KIS, appKey = "key", appSecret = "secret", accountNumber = "12345678")

        val saved = accountSlot.captured
        assertThat(saved.accessToken).startsWith("mock_token_")
        assertThat(saved.tokenExpiresAt).isNotNull()
        // ADR-025 — appKey/appSecret도 저장돼야 이후의 모든 KIS 호출이 가능하다.
        assertThat(saved.appKey).isEqualTo("key")
        assertThat(saved.appSecret).isEqualTo("secret")
    }

    @Test
    fun `다른 증권사로 재연동하면 기존 활성 계좌는 비활성화된다`() {
        val oldAccount = makeAccount().apply { }
        val accountSlots = mutableListOf<BrokerageAccount>()
        every { accountRepo.findByUserIdAndProviderAndAccountNumber(1L, BrokerageProvider.TOSS, "98765432") } returns Optional.empty()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(oldAccount)
        every { accountRepo.save(capture(accountSlots)) } answers { firstArg() }

        service.connect(userId = 1L, provider = BrokerageProvider.TOSS, appKey = "key2", appSecret = "secret2", accountNumber = "98765432")

        assertThat(oldAccount.isActive).isFalse()
        val newAccount = accountSlots.first { it !== oldAccount }
        assertThat(newAccount.provider).isEqualTo(BrokerageProvider.TOSS)
        assertThat(newAccount.accountNumber).isEqualTo("98765432")
        assertThat(newAccount.isActive).isTrue()
    }

    // ── submitOrder (MARKET) ──────────────────────────────────────────────────

    @Test
    fun `시장가 BUY 주문 제출 시 즉시 FILLED 되고 T+2 정산이 예약된다`() {
        val account      = makeAccount()
        val orderSlot    = slot<BrokerageOrder>()
        val settlSlot    = slot<BrokerageSettlement>()

        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        stubSubmitPersistence(account, orderSlot)
        every { settlementRepo.save(capture(settlSlot)) }     returns makeSettlement()
        stubStockLookup()
        every { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), any()) } returns approvedRisk

        // DB에서 현재가 조회 — Mock 클라이언트가 JdbcTemplate 호출
        every { jdbc.queryForObject(any<String>(), eq(BigDecimal::class.java), any()) } returns BigDecimal("70000")

        service.submitOrder(1L, BrokerageOrderRequest("005930", "BUY", "MARKET", 10))

        val order = orderSlot.captured
        assertThat(order.status).isEqualTo(BrokerageOrderStatus.FILLED)
        assertThat(order.filledQty).isEqualTo(10)
        assertThat(order.avgFillPrice).isNotNull()

        val settlement = settlSlot.captured
        assertThat(settlement.settleDate).isAfter(LocalDate.now())
        assertThat(settlement.fee).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `현재가 조회 실패 시 REJECTED 주문으로 저장된다`() {
        val account   = makeAccount()
        val orderSlot = slot<BrokerageOrder>()

        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        stubSubmitPersistence(account, orderSlot)
        stubStockLookup()
        every { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), any()) } returns approvedRisk
        // 리스크 스냅샷 조립에 쓰는 brokerage_orders 조회는 정상 응답시키고,
        // 현재가 조회(candles_1m)만 실패시켜 실제로 검증하려는 경로만 건드린다.
        every { jdbc.queryForObject(match<String> { it.contains("brokerage_orders") }, eq(BigDecimal::class.java), any()) } returns BigDecimal.ZERO
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), any()) } throws RuntimeException("DB error")

        service.submitOrder(1L, BrokerageOrderRequest("005930", "BUY", "MARKET", 10))

        assertThat(orderSlot.captured.status).isEqualTo(BrokerageOrderStatus.REJECTED)
    }

    @Test
    fun `리스크 게이트가 막으면 증권사에 주문을 보내지 않는다`() {
        val account = makeAccount()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { orderRepo.findAllByUserIdAndSymbolAndStatusIn(any(), any(), any()) } returns emptyList()
        stubStockLookup()
        every { jdbc.queryForObject(any<String>(), eq(BigDecimal::class.java), any()) } returns BigDecimal("70000")
        every { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), any()) } returns
            RiskCheckResult(approved = false, blockedBy = "DailyLossRule", severity = "BLOCKED", checks = emptyList())

        assertThrows<RiskLimitException> {
            service.submitOrder(1L, BrokerageOrderRequest("005930", "BUY", "MARKET", 10))
        }

        verify(exactly = 0) { orderRepo.save(any()) }
    }

    @Test
    fun `종목을 찾을 수 없으면 리스크 체크를 건너뛰지 않고 주문을 거부한다`() {
        val account = makeAccount()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, any()) } throws RuntimeException("not found")

        assertThrows<IllegalArgumentException> {
            service.submitOrder(1L, BrokerageOrderRequest("999999", "BUY", "MARKET", 10))
        }

        verify(exactly = 0) { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRepo.save(any()) }
    }

    // ── settle ────────────────────────────────────────────────────────────────

    @Test
    fun `settle 호출 시 SETTLED 상태로 전환되고 원장에 기록된다`() {
        val settlement = makeSettlement(status = BrokerageSettlementStatus.PENDING)
        every { settlementRepo.save(any()) } returns settlement

        service.settle(settlement)

        assertThat(settlement.status).isEqualTo(BrokerageSettlementStatus.SETTLED)
        assertThat(settlement.settledAt).isNotNull()
        verify {
            ledgerService.recordBrokerageSettlement(
                userId = settlement.userId,
                settlementId = settlement.id,
                symbol = settlement.symbol,
                side = settlement.side,
                netAmount = settlement.netAmount,
            )
        }
    }

    // ── cancelOrder (ADR-028 — 증권사에 실제로 취소를 전달한다) ─────────────────────

    @Test
    fun `SUBMITTED 주문 취소 성공 시 증권사에 취소를 전달하고 CANCELLED로 저장한다`() {
        val order = makeOrder(status = BrokerageOrderStatus.SUBMITTED, pgOrderId = "KIS123", brokerOrderRef = "00950")
        val account = makeAccount()
        val fakeClient = mockk<BrokerageClient>()
        every { orderRepo.findById(1L) } returns Optional.of(order)
        every { orderRepo.save(any()) } returns order
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { fakeClient.cancelOrder(any(), "KIS123", "00950") } returns BrokerageCancelResult(cancelled = true)

        serviceWithFakeClient(fakeClient).cancelOrder(userId = 1L, orderId = 1L)

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.CANCELLED)
        verify { fakeClient.cancelOrder(any(), "KIS123", "00950") }
    }

    @Test
    fun `증권사가 취소를 거부하면 로컬 상태는 CANCELLED로 바뀌지 않는다`() {
        val order = makeOrder(status = BrokerageOrderStatus.SUBMITTED, pgOrderId = "KIS123", brokerOrderRef = "00950")
        val account = makeAccount()
        val fakeClient = mockk<BrokerageClient>()
        every { orderRepo.findById(1L) } returns Optional.of(order)
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { fakeClient.cancelOrder(any(), "KIS123", "00950") } returns
            BrokerageCancelResult(cancelled = false, reason = "이미 체결된 주문입니다")

        assertThrows<BusinessRuleException> { serviceWithFakeClient(fakeClient).cancelOrder(userId = 1L, orderId = 1L) }

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.SUBMITTED)
        verify(exactly = 0) { orderRepo.save(any()) }
    }

    @Test
    fun `이미 FILLED된 주문 취소 시 예외 발생`() {
        val order = makeOrder(status = BrokerageOrderStatus.FILLED)
        every { orderRepo.findById(1L) } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            service.cancelOrder(userId = 1L, orderId = 1L)
        }
    }

    // ── 토큰 자동 재발급 (ADR-027) ────────────────────────────────────────────────

    private fun serviceWithFakeClient(fakeClient: BrokerageClient): BrokerageService {
        val registry = BrokerageClientRegistry(BrokerageProvider.entries.associateWith { fakeClient })
        return BrokerageService(registry, accountRepo, orderRepo, settlementRepo, ledgerService, riskChecker, jdbc, txManager, meterRegistry, haltService, pendingBuyQuery)
    }

    @Test
    fun `토큰이 만료됐으면 조용히 재발급하고 요청은 계속 진행된다`() {
        val account = makeAccount(tokenExpiresAt = Instant.now().minusSeconds(10))
        val fakeClient = mockk<BrokerageClient>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { fakeClient.issueToken("test-app-key", "test-app-secret") } returns BrokerageToken("new_token", 86400)
        every { accountRepo.save(any()) } answers { firstArg() }
        every { fakeClient.getBalance(any()) } returns BrokerageBalance(BigDecimal.TEN, BigDecimal.TEN, emptyList())

        serviceWithFakeClient(fakeClient).getBalance(1L)

        assertThat(account.accessToken).isEqualTo("new_token")
        assertThat(account.authFailedAt).isNull()
        verify { fakeClient.issueToken("test-app-key", "test-app-secret") }
    }

    @Test
    fun `재발급 실패 시 authFailedAt이 기록되고 재연동 필요 예외가 발생한다`() {
        val account = makeAccount(tokenExpiresAt = Instant.now().minusSeconds(10))
        val fakeClient = mockk<BrokerageClient>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { fakeClient.issueToken(any(), any()) } throws IllegalStateException("KIS 토큰 발급 실패")
        every { accountRepo.save(any()) } answers { firstArg() }

        assertThrows<ReconnectRequiredException> { serviceWithFakeClient(fakeClient).getBalance(1L) }

        assertThat(account.authFailedAt).isNotNull()
    }

    @Test
    fun `재발급 실패 쿨다운 이내면 재시도하지 않고 즉시 재연동 필요 예외를 던진다`() {
        val account = makeAccount(tokenExpiresAt = Instant.now().minusSeconds(10), authFailedAt = Instant.now().minusSeconds(60))
        val fakeClient = mockk<BrokerageClient>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)

        assertThrows<ReconnectRequiredException> { serviceWithFakeClient(fakeClient).getBalance(1L) }

        verify(exactly = 0) { fakeClient.issueToken(any(), any()) }
    }

    @Test
    fun `쿨다운이 지나면 다시 자동으로 재시도한다`() {
        val account = makeAccount(tokenExpiresAt = Instant.now().minusSeconds(10), authFailedAt = Instant.now().minusSeconds(400))
        val fakeClient = mockk<BrokerageClient>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { fakeClient.issueToken(any(), any()) } returns BrokerageToken("new_token", 86400)
        every { accountRepo.save(any()) } answers { firstArg() }
        every { fakeClient.getBalance(any()) } returns BrokerageBalance(BigDecimal.TEN, BigDecimal.TEN, emptyList())

        serviceWithFakeClient(fakeClient).getBalance(1L)

        verify { fakeClient.issueToken(any(), any()) }
        assertThat(account.authFailedAt).isNull()
    }

    @Test
    fun `재연동하면 이전 재발급 실패 기록이 지워진다`() {
        val oldAccount = makeAccount(authFailedAt = Instant.now())
        val accountSlot = slot<BrokerageAccount>()
        every { accountRepo.findByUserIdAndProviderAndAccountNumber(1L, BrokerageProvider.KIS, "12345678") } returns Optional.of(oldAccount)
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(oldAccount)
        every { accountRepo.save(capture(accountSlot)) } answers { firstArg() }

        service.connect(userId = 1L, provider = BrokerageProvider.KIS, appKey = "key", appSecret = "secret", accountNumber = "12345678")

        assertThat(accountSlot.captured.authFailedAt).isNull()
    }

    // ── ADR-056 — 결과 불명 ──────────────────────────────────────────────────────

    private val req = BrokerageOrderRequest("005930", "SELL", "MARKET", 10)

    /** 리스크 게이트·잔고·종목 조회를 통과시키고, 증권사 결과만 테스트가 정하게 한다. */
    private fun fakeClientService(result: BrokerageOrderResult, orderSlot: CapturingSlot<BrokerageOrder>): Pair<BrokerageService, BrokerageClient> {
        val account = makeAccount()
        val fakeClient = mockk<BrokerageClient>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        stubSubmitPersistence(account, orderSlot)
        stubStockLookup()
        every { jdbc.queryForObject(any<String>(), eq(BigDecimal::class.java), any()) } returns BigDecimal("70000")
        every { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), any()) } returns approvedRisk
        every { fakeClient.getBalance(any()) } returns BrokerageBalance(BigDecimal("10000000"), BigDecimal("10000000"), emptyList())
        every { fakeClient.submitOrder(any(), any(), any()) } returns result
        every { fakeClient.getOrderStatus(any(), any()) } answers {
            com.monticker.api.brokerage.infrastructure.BrokerageOrderStatus(secondArg(), "SUBMITTED", 0, null)
        }
        return serviceWithFakeClient(fakeClient) to fakeClient
    }

    @Test
    fun `응답 없음(INDETERMINATE)은 거부가 아니라 UNKNOWN으로 기록한다 — 재주문하면 이중 주문이다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, _) = fakeClientService(BrokerageOrderResult.indeterminate("read timeout"), orderSlot)

        val order = svc.submitOrder(1L, req)

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.UNKNOWN)
        assertThat(order.pgOrderId).isNull()
        assertThat(meterRegistry.find("brokerage_order_submit_total").tag("outcome", "indeterminate").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `확정 거부는 REJECTED다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, _) = fakeClientService(BrokerageOrderResult.rejected("주문가능수량 초과"), orderSlot)

        assertThat(svc.submitOrder(1L, req).status).isEqualTo(BrokerageOrderStatus.REJECTED)
    }

    @Test
    fun `의도(PENDING_SUBMIT)를 증권사 호출보다 먼저 저장하고, 같은 clientOrderId를 증권사에 보낸다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, fakeClient) = fakeClientService(BrokerageOrderResult.accepted("ODNO-1"), orderSlot)
        val savedStatuses = mutableListOf<BrokerageOrderStatus>()
        val savedClientIds = mutableListOf<String?>()
        every { orderRepo.save(capture(orderSlot)) } answers {
            savedStatuses += firstArg<BrokerageOrder>().status
            savedClientIds += firstArg<BrokerageOrder>().clientOrderId
            firstArg()
        }
        val sentClientId = slot<String>()
        every { fakeClient.submitOrder(any(), any(), capture(sentClientId)) } answers {
            // 증권사가 불리는 시점에 의도는 이미 저장돼 있어야 한다
            assertThat(savedStatuses).containsExactly(BrokerageOrderStatus.PENDING_SUBMIT)
            BrokerageOrderResult.accepted("ODNO-1")
        }

        val order = svc.submitOrder(1L, req)

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.SUBMITTED)
        assertThat(order.pgOrderId).isEqualTo("ODNO-1")
        assertThat(sentClientId.captured).isEqualTo(savedClientIds.first()).startsWith("mt-")
        assertThat(sentClientId.captured.length).isLessThanOrEqualTo(36)   // Toss clientOrderId 상한
    }

    @Test
    fun `같은 종목·방향의 결과 불명 주문이 있으면 새 주문을 증권사에 보내지 않고 409로 막는다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, fakeClient) = fakeClientService(BrokerageOrderResult.accepted("ODNO-2"), orderSlot)
        every { orderRepo.findAllByUserIdAndSymbolAndStatusIn(1L, "005930", any()) } returns
            listOf(makeOrder(status = BrokerageOrderStatus.UNKNOWN).apply { /* side=BUY */ }, makeSellOrder(BrokerageOrderStatus.UNKNOWN))

        assertThrows<BusinessRuleException> { svc.submitOrder(1L, req) }

        verify(exactly = 0) { fakeClient.submitOrder(any(), any(), any()) }
        verify(exactly = 0) { orderRepo.save(any()) }
    }

    @Test
    fun `반대 방향의 결과 불명 주문은 막지 않는다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, _) = fakeClientService(BrokerageOrderResult.accepted("ODNO-3"), orderSlot)
        every { orderRepo.findAllByUserIdAndSymbolAndStatusIn(1L, "005930", any()) } returns
            listOf(makeOrder(status = BrokerageOrderStatus.UNKNOWN))   // BUY

        assertThat(svc.submitOrder(1L, req).status).isEqualTo(BrokerageOrderStatus.SUBMITTED)
    }

    @Test
    fun `결과 기록(tx2)이 실패하면 OrderOutcomeUnknownException — 호출자는 주문이 나갔을 수 있음을 안다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, _) = fakeClientService(BrokerageOrderResult.accepted("ODNO-4"), orderSlot)
        every { orderRepo.findWithLockById(any()) } throws RuntimeException("db down")

        val e = assertThrows<OrderOutcomeUnknownException> { svc.submitOrder(1L, req) }

        assertThat(e.orderId).isEqualTo(orderSlot.captured.id)
    }

    @Test
    fun `대조 잡이 먼저 해소한 주문은 늦게 도착한 제출 결과로 덮어쓰지 않는다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, _) = fakeClientService(BrokerageOrderResult.indeterminate("late"), orderSlot)
        val resolved = makeSellOrder(BrokerageOrderStatus.FILLED)
        every { orderRepo.findWithLockById(any()) } returns resolved

        assertThat(svc.submitOrder(1L, req).status).isEqualTo(BrokerageOrderStatus.FILLED)
    }

    // ── ADR-056 — 대조 ────────────────────────────────────────────────────────

    private fun stubReconcile(order: BrokerageOrder, snapshots: List<BrokerOrderSnapshot>?, locked: Boolean = true): BrokerageClient {
        val account = makeAccount()
        val fakeClient = mockk<BrokerageClient>()
        every { jdbc.queryForList(match<String> { it.contains("SKIP LOCKED") }, eq(Long::class.java), any()) } returns
            if (locked) listOf(order.id) else emptyList()
        every { jdbc.queryForList(match<String> { it.contains("SELECT pg_order_id") }, eq(String::class.java), *anyVararg()) } returns emptyList()
        every { orderRepo.findById(order.id) } returns Optional.of(order)
        every { orderRepo.save(any()) } answers { firstArg() }
        every { accountRepo.findById(account.id) } returns Optional.of(account)
        every { settlementRepo.save(any()) } answers { firstArg() }
        every { fakeClient.findOrders(any(), any(), "005930", "SELL") } returns snapshots
        return fakeClient
    }

    private fun snapshot(id: String, at: Instant, status: String = "FILLED") = BrokerOrderSnapshot(
        brokerOrderId = id, brokerOrderRef = "06010", symbol = "005930", side = "SELL", quantity = 10,
        price = null, orderedAt = at, status = status, filledQty = 10, avgFillPrice = BigDecimal("70000"),
    )

    @Test
    fun `대조 — 후보가 정확히 1건이면 그 증권사 주문에 연결하고 체결까지 반영한다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN, submittedAt = Instant.now().minusSeconds(40))
        val client = stubReconcile(order, listOf(snapshot("ODNO-9", order.submittedAt.plusSeconds(1))))

        val result = serviceWithFakeClient(client).reconcileUnresolved(order.id)

        assertThat(result).isEqualTo(BrokerageService.ReconcileResult.MATCHED)
        assertThat(order.pgOrderId).isEqualTo("ODNO-9")
        assertThat(order.brokerOrderRef).isEqualTo("06010")
        assertThat(order.status).isEqualTo(BrokerageOrderStatus.FILLED)
        assertThat(order.resolvedBy).isEqualTo(OrderResolution.BROKER_LOOKUP)
        verify { settlementRepo.save(any()) }
    }

    @Test
    fun `대조 — 해소되지 않으면 다음 대조를 뒤로 미룬다(30s, 60s, … 상한 10분) — 고갈 방지`() {
        val now = Instant.now()
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN, submittedAt = now.minusSeconds(40))
        val client = stubReconcile(order, snapshots = null)
        val svc = serviceWithFakeClient(client)

        svc.reconcileUnresolved(order.id, now)
        assertThat(order.nextReconcileAt).isEqualTo(now.plusSeconds(30))
        svc.reconcileUnresolved(order.id, now)
        assertThat(order.nextReconcileAt).isEqualTo(now.plusSeconds(60))
        repeat(8) { svc.reconcileUnresolved(order.id, now) }
        assertThat(order.nextReconcileAt).isEqualTo(now.plusSeconds(600))
    }

    @Test
    fun `미접수로 확정된 뒤 접수 응답이 도착하면 증권사 응답을 따라 접수로 정정한다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, _) = fakeClientService(BrokerageOrderResult.accepted("ODNO-LATE"), orderSlot)
        val wronglyRejected = makeSellOrder(BrokerageOrderStatus.REJECTED).apply { resolvedBy = OrderResolution.NOT_FOUND }
        every { orderRepo.findWithLockById(any()) } returns wronglyRejected

        val order = svc.submitOrder(1L, req)

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.SUBMITTED)
        assertThat(order.pgOrderId).isEqualTo("ODNO-LATE")
        assertThat(order.resolvedBy).isNull()
    }

    @Test
    fun `대조 — 증권사 조회 실패는 "주문 없음"으로 읽지 않는다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN, submittedAt = Instant.now().minusSeconds(600))
        val client = stubReconcile(order, snapshots = null)

        val result = serviceWithFakeClient(client).reconcileUnresolved(order.id)

        assertThat(result).isEqualTo(BrokerageService.ReconcileResult.LOOKUP_FAILED)
        assertThat(order.status).isEqualTo(BrokerageOrderStatus.UNKNOWN)
        assertThat(order.reconcileAttempts).isEqualTo(1)
    }

    @Test
    fun `대조 — 유예가 지나도 후보가 없으면 미접수로 확정한다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN, submittedAt = Instant.now().minusSeconds(180))
        val client = stubReconcile(order, emptyList())

        assertThat(serviceWithFakeClient(client).reconcileUnresolved(order.id)).isEqualTo(BrokerageService.ReconcileResult.NOT_FOUND)
        assertThat(order.status).isEqualTo(BrokerageOrderStatus.REJECTED)
        assertThat(order.resolvedBy).isEqualTo(OrderResolution.NOT_FOUND)
    }

    @Test
    fun `대조 — 후보가 둘이면 고르지 않고 수동 검토로 넘긴다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN, submittedAt = Instant.now().minusSeconds(40))
        val client = stubReconcile(order, listOf(
            snapshot("A", order.submittedAt.plusSeconds(1)), snapshot("B", order.submittedAt.plusSeconds(2)),
        ))

        assertThat(serviceWithFakeClient(client).reconcileUnresolved(order.id)).isEqualTo(BrokerageService.ReconcileResult.AMBIGUOUS)
        assertThat(order.status).isEqualTo(BrokerageOrderStatus.UNKNOWN)
        assertThat(order.needsReview).isTrue()
    }

    @Test
    fun `대조 — 다른 레플리카가 행을 잡고 있으면 건너뛴다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN, submittedAt = Instant.now().minusSeconds(40))
        val client = stubReconcile(order, emptyList(), locked = false)

        assertThat(serviceWithFakeClient(client).reconcileUnresolved(order.id)).isEqualTo(BrokerageService.ReconcileResult.SKIPPED)
        verify(exactly = 0) { client.findOrders(any(), any(), any(), any()) }
    }

    @Test
    fun `대조 — 막 기록된 PENDING_SUBMIT은 아직 호출 중일 수 있어 건드리지 않는다`() {
        val order = makeSellOrder(BrokerageOrderStatus.PENDING_SUBMIT, submittedAt = Instant.now().minusSeconds(5))
        val client = stubReconcile(order, emptyList())

        assertThat(serviceWithFakeClient(client).reconcileUnresolved(order.id)).isEqualTo(BrokerageService.ReconcileResult.SKIPPED)
        verify(exactly = 0) { client.findOrders(any(), any(), any(), any()) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeAccount(
        tokenExpiresAt: Instant = Instant.now().plusSeconds(86400),
        authFailedAt: Instant? = null,
    ) = BrokerageAccount(
        id = 1L, userId = 1L, accountNumber = "12345678",
        accessToken = "mock_token_test", tokenExpiresAt = tokenExpiresAt,
        appKey = "test-app-key", appSecret = "test-app-secret", authFailedAt = authFailedAt,
    )

    private fun makeOrder(
        status: BrokerageOrderStatus = BrokerageOrderStatus.SUBMITTED,
        pgOrderId: String? = null,
        brokerOrderRef: String? = null,
    ) = BrokerageOrder(
        id = 1L, userId = 1L, accountId = 1L, symbol = "005930",
        side = OrderSide.BUY, orderType = OrderType.MARKET, quantity = 10,
        status = status, pgOrderId = pgOrderId, brokerOrderRef = brokerOrderRef,
    )

    private fun makeSellOrder(status: BrokerageOrderStatus, submittedAt: Instant = Instant.now()) = BrokerageOrder(
        id = 7L, userId = 1L, accountId = 1L, symbol = "005930",
        side = OrderSide.SELL, orderType = OrderType.MARKET, quantity = 10,
        status = status, submittedAt = submittedAt, clientOrderId = "mt-test",
    )

    private fun makeSettlement(status: BrokerageSettlementStatus = BrokerageSettlementStatus.PENDING) =
        BrokerageSettlement(
            id = 1L, userId = 1L, accountId = 1L, symbol = "005930", side = "BUY",
            quantity = 10, fillPrice = BigDecimal("70000"),
            grossAmount = BigDecimal("700000"), fee = BigDecimal("105"), tax = BigDecimal.ZERO,
            netAmount = BigDecimal("700105"), settleDate = LocalDate.now().plusDays(2),
            status = status,
        )

    // ── ADR-057 — 킬 스위치 ──────────────────────────────────────────────────────

    @Test
    fun `킬 스위치가 켜져 있으면 의도 기록도 증권사 호출도 없이 TradingHaltedException`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, fakeClient) = fakeClientService(BrokerageOrderResult.accepted("ODNO-H"), orderSlot)
        every { haltService.findActive(any(), 1L) } returns TradingHalt(
            id = 1, scope = HaltScope.GLOBAL, target = null, reason = "점검", haltedBy = 9, haltedAt = Instant.now(),
            liftedBy = null, liftedAt = null, liftReason = null,
        )

        assertThrows<com.monticker.api.common.exception.TradingHaltedException> { svc.submitOrder(1L, req) }

        verify(exactly = 0) { orderRepo.save(any()) }
        verify(exactly = 0) { fakeClient.submitOrder(any(), any(), any()) }
        verify(exactly = 0) { fakeClient.issueToken(any(), any()) }   // 자격증명 재발급(증권사 호출)보다 먼저 막힌다
        assertThat(meterRegistry.find("brokerage_order_blocked_by_halt_total").tag("scope", "GLOBAL").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `사용자 범위 스위치는 사용자에게 사유를 숨긴다`() {
        val halt = TradingHalt(1, HaltScope.USER, "1", "자격증명 유출 의심", 9, Instant.now(), null, null, null)
        assertThat(halt.userMessage).doesNotContain("유출").contains("고객센터")
        assertThat(halt.copy(scope = HaltScope.GLOBAL, target = null, reason = "증권사 점검").userMessage).contains("증권사 점검")
    }

    // ── ADR-058 — 진행 중 매수 갱신 ──────────────────────────────────────────────────────

    @Test
    fun `리스크 게이트 직전에 같은 종목의 미체결 매수를 증권사에서 갱신한다 — 체결된 것이 보유와 대기에 이중으로 잡히지 않게`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, fakeClient) = fakeClientService(BrokerageOrderResult.accepted("ODNO-NEW"), orderSlot)
        val open = makeOrder(status = BrokerageOrderStatus.SUBMITTED, pgOrderId = "ODNO-OPEN")
        every { orderRepo.findAllByAccountIdAndStockIdAndSideAndStatusAndSubmittedAtAfter(1L, 1L, OrderSide.BUY, BrokerageOrderStatus.SUBMITTED, any()) } returns listOf(open)
        every { fakeClient.getOrderStatus(any(), "ODNO-OPEN") } returns
            com.monticker.api.brokerage.infrastructure.BrokerageOrderStatus("ODNO-OPEN", "FILLED", 10, BigDecimal("70000"))
        every { settlementRepo.save(any()) } answers { firstArg() }

        svc.submitOrder(1L, BrokerageOrderRequest("005930", "BUY", "MARKET", 1))

        assertThat(open.status).isEqualTo(BrokerageOrderStatus.FILLED)
        verify(ordering = io.mockk.Ordering.ORDERED) {
            orderRepo.save(open)
            pendingBuyQuery.pendingBuys(any(), any())   // 갱신이 대기 집계보다 먼저다
        }
    }

    @Test
    fun `보유 내역은 수량 0 행을 빼고 같은 종목 여러 행을 합친다`() {
        val orderSlot = slot<BrokerageOrder>()
        val (svc, fakeClient) = fakeClientService(BrokerageOrderResult.accepted("ODNO-H"), orderSlot)
        every { fakeClient.getBalance(any()) } returns BrokerageBalance(
            BigDecimal("10000000"), BigDecimal("10000000"),
            listOf(
                com.monticker.api.brokerage.infrastructure.BrokerageHolding("005930", 3, BigDecimal.ONE, BigDecimal.ONE),
                com.monticker.api.brokerage.infrastructure.BrokerageHolding("005930", 4, BigDecimal.ONE, BigDecimal.ONE),
                com.monticker.api.brokerage.infrastructure.BrokerageHolding("000660", 0, BigDecimal.ONE, BigDecimal.ONE),
            ),
        )
        val snapshot = slot<com.monticker.api.risk.application.PortfolioSnapshot>()
        every { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), capture(snapshot)) } returns approvedRisk

        svc.submitOrder(1L, req)

        assertThat(snapshot.captured.holdings).containsExactly(com.monticker.api.risk.application.HoldingPosition(stockId = 1L, qty = 7))
        assertThat(snapshot.captured.totalAssets).isEqualByComparingTo(BigDecimal("10000000"))
    }

    // ── ADR-056 Note — 관리자 수동 확정 ─────────────────────────────────────────────────

    private fun stubManual(order: BrokerageOrder, snapshots: List<BrokerOrderSnapshot>?, linkedElsewhere: Long = 0): BrokerageClient {
        val account = makeAccount()
        val fakeClient = mockk<BrokerageClient>()
        every { orderRepo.findWithLockById(order.id) } returns order
        every { orderRepo.save(any()) } answers { firstArg() }
        every { accountRepo.findById(account.id) } returns Optional.of(account)
        every { settlementRepo.save(any()) } answers { firstArg() }
        every { fakeClient.findOrders(any(), any(), "005930", "SELL") } returns snapshots
        every { jdbc.queryForObject(match<String> { it.contains("pg_order_id = ? AND id <> ?") }, eq(Long::class.java), *anyVararg()) } returns linkedElsewhere
        return fakeClient
    }

    @Test
    fun `수동 확정 — 지정한 증권사 주문을 당일 목록에서 다시 찾아 연결하고 체결까지 반영한다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN).apply { needsReview = true }
        val client = stubManual(order, listOf(snapshot("B", order.submittedAt), snapshot("A", order.submittedAt)))

        val resolved = serviceWithFakeClient(client).resolveManually(order.id, 9L, "B", notPlaced = false, note = "HTS 내역 대조")

        assertThat(resolved.pgOrderId).isEqualTo("B")
        assertThat(resolved.status).isEqualTo(BrokerageOrderStatus.FILLED)
        assertThat(resolved.resolvedBy).isEqualTo(OrderResolution.MANUAL)
        assertThat(resolved.resolvedByUser).isEqualTo(9L)
        assertThat(resolved.needsReview).isFalse()
    }

    @Test
    fun `수동 확정 — 미접수로 확정할 수 있다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN)
        val client = stubManual(order, emptyList())

        val resolved = serviceWithFakeClient(client).resolveManually(order.id, 9L, null, notPlaced = true, note = "증권사 콜센터 확인")

        assertThat(resolved.status).isEqualTo(BrokerageOrderStatus.REJECTED)
        assertThat(resolved.resolutionNote).isEqualTo("증권사 콜센터 확인")
        verify(exactly = 0) { client.findOrders(any(), any(), any(), any()) }
    }

    @Test
    fun `수동 확정 — 목록에 없는 번호, 수량이 다른 주문, 이미 연결된 번호는 거부한다`() {
        val order = makeSellOrder(BrokerageOrderStatus.UNKNOWN)
        assertThrows<BusinessRuleException> {
            serviceWithFakeClient(stubManual(order, listOf(snapshot("A", order.submittedAt)))).resolveManually(order.id, 9L, "Z", false, "x")
        }
        assertThrows<BusinessRuleException> {
            serviceWithFakeClient(stubManual(order, listOf(snapshot("A", order.submittedAt).copy(quantity = 3)))).resolveManually(order.id, 9L, "A", false, "x")
        }
        assertThrows<BusinessRuleException> {
            serviceWithFakeClient(stubManual(order, listOf(snapshot("A", order.submittedAt)), linkedElsewhere = 1)).resolveManually(order.id, 9L, "A", false, "x")
        }
        assertThat(order.status).isEqualTo(BrokerageOrderStatus.UNKNOWN)
    }

    @Test
    fun `수동 확정 — 결과를 아는 주문은 건드리지 않고, 번호와 미접수를 함께 주거나 둘 다 안 주면 거부한다`() {
        val filled = makeSellOrder(BrokerageOrderStatus.FILLED)
        assertThrows<BusinessRuleException> { serviceWithFakeClient(stubManual(filled, emptyList())).resolveManually(filled.id, 9L, null, true, "x") }
        val unknown = makeSellOrder(BrokerageOrderStatus.UNKNOWN)
        val svc = serviceWithFakeClient(stubManual(unknown, emptyList()))
        assertThrows<IllegalArgumentException> { svc.resolveManually(unknown.id, 9L, "A", true, "x") }
        assertThrows<IllegalArgumentException> { svc.resolveManually(unknown.id, 9L, null, false, "x") }
    }
}
