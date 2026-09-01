package com.monticker.api.paper.application

import com.monticker.api.common.domain.Money
import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.domain.SettlementStatus
import com.monticker.api.paper.events.PaperSettlementCompletedEvent
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional

class PaperSettlementServiceTest {

    private val settlementRepo  = mockk<PaperSettlementRepository>()
    private val accountRepo     = mockk<PaperAccountRepository>()
    private val eventPublisher  = mockk<ApplicationEventPublisher>(relaxed = true)

    private val service = PaperSettlementService(settlementRepo, accountRepo, eventPublisher)

    private val slot = slot<PaperSettlement>()

    @BeforeEach
    fun setUp() {
        every { settlementRepo.save(capture(slot)) } answers { slot.captured }
    }

    // ── createPending ─────────────────────────────────────────────────────────

    @Test
    fun `BUY 체결 시 T+2 영업일로 PENDING 정산 레코드 생성된다`() {
        val trade = makeTrade("BUY", price = BigDecimal("70000"), qty = 10)

        service.createPending(trade)

        val saved = slot.captured
        assertThat(saved.side).isEqualTo("BUY")
        assertThat(saved.status).isEqualTo(SettlementStatus.PENDING)
        assertThat(saved.settleDate).isAfter(LocalDate.now())
        assertThat(saved.grossAmount).isEqualByComparingTo(BigDecimal("700000"))
        // BUY: net = gross + fee  →  net > gross
        assertThat(saved.netAmount).isGreaterThan(saved.grossAmount)
    }

    @Test
    fun `SELL 체결 시 수수료와 세금이 모두 계산된다`() {
        val trade = makeTrade("SELL", price = BigDecimal("70000"), qty = 10)

        service.createPending(trade)

        val saved = slot.captured
        assertThat(saved.fee).isGreaterThan(BigDecimal.ZERO)
        assertThat(saved.tax).isGreaterThan(BigDecimal.ZERO)
        // SELL: net = gross - fee - tax  →  net < gross
        assertThat(saved.netAmount).isLessThan(saved.grossAmount)
    }

    @Test
    fun `T+2 영업일 계산 시 주말을 건너뛴다`() {
        // BusinessDayCalculator 단독 검증
        val friday = LocalDate.of(2025, 8, 1)   // 금요일
        val result = BusinessDayCalculator.addBusinessDays(friday, 2)
        // 토·일 건너뛰므로 화요일(8/5)이어야 한다
        assertThat(result).isEqualTo(LocalDate.of(2025, 8, 5))
    }

    // ── settle ────────────────────────────────────────────────────────────────

    @Test
    fun `settle 호출 시 수수료+세금 차감 후 SETTLED로 전환된다`() {
        val account    = makeAccount(cash = BigDecimal("1000000"))
        val settlement = makePendingSettlement(fee = BigDecimal("105"), tax = BigDecimal("1260"))

        every { accountRepo.findByUserId(settlement.userId) } returns Optional.of(account)
        every { accountRepo.save(any()) } returns account

        service.settle(settlement)

        assertThat(settlement.status).isEqualTo(SettlementStatus.SETTLED)
        assertThat(settlement.settledAt).isNotNull()
        // 수수료+세금 = 1365 차감
        assertThat(account.cash.amount).isEqualByComparingTo(BigDecimal("998635"))
        verify { eventPublisher.publishEvent(any<PaperSettlementCompletedEvent>()) }
    }

    @Test
    fun `계정이 없으면 FAILED 상태로 전환된다`() {
        val settlement = makePendingSettlement()
        every { accountRepo.findByUserId(settlement.userId) } returns Optional.empty()

        service.settle(settlement)

        assertThat(settlement.status).isEqualTo(SettlementStatus.FAILED)
        verify(exactly = 0) { eventPublisher.publishEvent(any<PaperSettlementCompletedEvent>()) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeTrade(side: String, price: BigDecimal, qty: Int) = PaperTrade(
        id = 1L, userId = 10L, stockId = 100L,
        side = side, quantity = qty, price = price,
        amount = price.multiply(BigDecimal(qty)),
    )

    private fun makeAccount(cash: BigDecimal) = PaperAccount(
        id = 1L, userId = 10L,
        cash = Money(cash),
    )

    private fun makePendingSettlement(
        fee: BigDecimal = BigDecimal("105"),
        tax: BigDecimal = BigDecimal("1260"),
    ) = PaperSettlement(
        id          = 1L,
        tradeId     = 1L,
        userId      = 10L,
        stockId     = 100L,
        side        = "SELL",
        quantity    = 10,
        fillPrice   = BigDecimal("70000"),
        grossAmount = BigDecimal("700000"),
        fee         = fee,
        tax         = tax,
        netAmount   = BigDecimal("700000").subtract(fee).subtract(tax),
        settleDate  = LocalDate.now(),
    )
}
