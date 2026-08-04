package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRepository
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.brokerage.infrastructure.MockBrokerageClient
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
    private val accountRepo    = mockk<BrokerageAccountRepository>()
    private val orderRepo      = mockk<BrokerageOrderRepository>()
    private val settlementRepo = mockk<BrokerageSettlementRepository>()
    private val ledgerService  = mockk<LedgerService>(relaxed = true)

    private val service = BrokerageService(mockClient, accountRepo, orderRepo, settlementRepo, ledgerService)

    // ── connect ───────────────────────────────────────────────────────────────

    @Test
    fun `계좌 연동 시 Mock 토큰이 발급되고 저장된다`() {
        val accountSlot = slot<BrokerageAccount>()
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.empty()
        every { accountRepo.save(capture(accountSlot)) }      returns makeAccount()

        service.connect(userId = 1L, appKey = "key", appSecret = "secret", accountNumber = "12345678")

        val saved = accountSlot.captured
        assertThat(saved.accessToken).startsWith("mock_token_")
        assertThat(saved.tokenExpiresAt).isNotNull()
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
        every { jdbc.queryForObject(any<String>(), eq(BigDecimal::class.java), any()) } throws RuntimeException("DB error")

        service.submitOrder(1L, BrokerageOrderRequest("005930", "BUY", "MARKET", 10))

        assertThat(orderSlot.captured.status).isEqualTo(BrokerageOrderStatus.REJECTED)
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
