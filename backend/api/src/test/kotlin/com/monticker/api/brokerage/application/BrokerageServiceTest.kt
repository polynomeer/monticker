package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRepository
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.brokerage.infrastructure.MockBrokerageClient
import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.wallet.application.LedgerService
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
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

    private val service = BrokerageService(clientRegistry, accountRepo, orderRepo, settlementRepo, ledgerService, riskChecker, jdbc)

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
        every { orderRepo.save(capture(orderSlot)) }          returns makeOrder()
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
        every { orderRepo.save(capture(orderSlot)) }          returns makeOrder()
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
    fun `종목을 찾을 수 없으면 리스크 체크를 건너뛰고 주문은 진행한다`() {
        val account   = makeAccount()
        val orderSlot = slot<BrokerageOrder>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(account)
        every { orderRepo.save(capture(orderSlot)) } returns makeOrder()
        every { settlementRepo.save(any()) } returns makeSettlement()
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, any()) } throws RuntimeException("not found")
        every { jdbc.queryForObject(any<String>(), eq(BigDecimal::class.java), any()) } returns BigDecimal("70000")

        service.submitOrder(1L, BrokerageOrderRequest("999999", "BUY", "MARKET", 10))

        assertThat(orderSlot.captured.stockId).isNull()
        verify(exactly = 0) { riskChecker.checkBrokerageOrder(any(), any(), any(), any(), any(), any()) }
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

    // ── cancelOrder ───────────────────────────────────────────────────────────

    @Test
    fun `SUBMITTED 주문 취소 성공`() {
        val order = makeOrder(status = BrokerageOrderStatus.SUBMITTED)
        every { orderRepo.findById(1L) }    returns Optional.of(order)
        every { orderRepo.save(any()) }     returns order

        service.cancelOrder(userId = 1L, orderId = 1L)

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.CANCELLED)
    }

    @Test
    fun `이미 FILLED된 주문 취소 시 예외 발생`() {
        val order = makeOrder(status = BrokerageOrderStatus.FILLED)
        every { orderRepo.findById(1L) } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            service.cancelOrder(userId = 1L, orderId = 1L)
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeAccount() = BrokerageAccount(
        id = 1L, userId = 1L, accountNumber = "12345678",
        accessToken = "mock_token_test", tokenExpiresAt = java.time.Instant.now().plusSeconds(86400),
        appKey = "test-app-key", appSecret = "test-app-secret",
    )

    private fun makeOrder(status: BrokerageOrderStatus = BrokerageOrderStatus.SUBMITTED) = BrokerageOrder(
        id = 1L, userId = 1L, accountId = 1L, symbol = "005930",
        side = OrderSide.BUY, orderType = OrderType.MARKET, quantity = 10,
        status = status,
    )

    private fun makeSettlement(status: BrokerageSettlementStatus = BrokerageSettlementStatus.PENDING) =
        BrokerageSettlement(
            id = 1L, userId = 1L, accountId = 1L, symbol = "005930", side = "BUY",
            quantity = 10, fillPrice = BigDecimal("70000"),
            grossAmount = BigDecimal("700000"), fee = BigDecimal("105"), tax = BigDecimal.ZERO,
            netAmount = BigDecimal("700105"), settleDate = LocalDate.now().plusDays(2),
            status = status,
        )
}
