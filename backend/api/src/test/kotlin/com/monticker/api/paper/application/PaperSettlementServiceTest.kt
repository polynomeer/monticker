package com.monticker.api.paper.application

import com.monticker.api.common.calendar.V83Seed
import com.monticker.api.common.domain.Money
import com.monticker.api.paper.infrastructure.SettlementDayStatusSum
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

    private val calendar        = V83Seed.calendar()

    private val service = PaperSettlementService(settlementRepo, accountRepo, eventPublisher, calendar)

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
        assertThat(saved.settleDate).isAfter(trade.tradedAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate())
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
        // 2026-08-07(금) 10:00 KST 체결 → 토·일 건너뛰고 8/10(월) T+1, 8/11(화) T+2
        service.createPending(makeTrade("BUY", BigDecimal("1000"), 1, java.time.Instant.parse("2026-08-07T01:00:00Z")))
        assertThat(slot.captured.settleDate).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    @Test
    fun `추석 전 영업일 체결은 연휴와 주말을 건너뛰어 정산된다`() {
        // 2026-09-23(수) 14:00 KST 체결 → 9/24·25 추석, 9/26·27 주말 → 9/28(월) T+1, 9/29(화) T+2
        service.createPending(makeTrade("SELL", BigDecimal("1000"), 1, java.time.Instant.parse("2026-09-23T05:00:00Z")))
        assertThat(slot.captured.settleDate).isEqualTo(LocalDate.of(2026, 9, 29))
    }

    @Test
    fun `광복절 대체공휴일 직전 금요일 체결`() {
        // 2026-08-14(금) → 8/15·16 주말, 8/17 광복절 대체공휴일 → 8/18 T+1, 8/19 T+2
        service.createPending(makeTrade("BUY", BigDecimal("1000"), 1, java.time.Instant.parse("2026-08-14T01:00:00Z")))
        assertThat(slot.captured.settleDate).isEqualTo(LocalDate.of(2026, 8, 19))
    }

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Test
    fun `status 필터가 있으면 상태별 페이지 쿼리를 쓴다`() {
        val pageable = org.springframework.data.domain.PageRequest.of(0, 20)
        every { settlementRepo.findAllByUserIdAndStatusOrderBySettleDateDesc(10L, SettlementStatus.SETTLED, pageable) } returns org.springframework.data.domain.Page.empty()
        service.getSettlements(10L, pageable, SettlementStatus.SETTLED)
        verify { settlementRepo.findAllByUserIdAndStatusOrderBySettleDateDesc(10L, SettlementStatus.SETTLED, pageable) }
        verify(exactly = 0) { settlementRepo.findAllByUserIdOrderBySettleDateDesc(any(), any()) }
    }

    @Test
    fun `기간 집계는 매수를 음수로 더하고 FAILED를 빼며 휴장일을 붙인다`() {
        val from = LocalDate.of(2026, 9, 21)
        val to = LocalDate.of(2026, 9, 27)
        every { settlementRepo.sumByDateAndStatus(10L, from, to) } returns listOf(
            SettlementDayStatusSum(LocalDate.of(2026, 9, 22), SettlementStatus.SETTLED, BigDecimal("-700105"), 1),
            SettlementDayStatusSum(LocalDate.of(2026, 9, 23), SettlementStatus.PENDING, BigDecimal("500000"), 2),
            SettlementDayStatusSum(LocalDate.of(2026, 9, 23), SettlementStatus.FAILED, BigDecimal("999"), 1),
        )

        val s = service.getSummary(10L, from, to)

        assertThat(s.pendingNet).isEqualByComparingTo("500000")
        assertThat(s.settledNet).isEqualByComparingTo("-700105")
        assertThat(s.totalNet).isEqualByComparingTo("-200105")
        assertThat(s.count).isEqualTo(3)
        assertThat(s.byDate.map { it.date }).containsExactly(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 23))
        assertThat(s.byDate[1].net).isEqualByComparingTo("500000")
        assertThat(s.holidays.map { it.second }).containsExactly("추석 연휴", "추석")
    }

    @Test
    fun `기간 집계는 거꾸로 된 기간을 거부한다`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            service.getSummary(10L, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 21))
        }.isInstanceOf(IllegalArgumentException::class.java)
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

    @Test
    fun `getByTradeId는 내 거래의 정산만 돌려주고 남의 거래는 없는 거래와 똑같이 null이다`() {
        val settlement = makePendingSettlement()   // userId = 10
        every { settlementRepo.findByTradeId(1L) } returns settlement
        assertThat(service.getByTradeId(10L, 1L)).isSameAs(settlement)
        assertThat(service.getByTradeId(99L, 1L)).isNull()

        every { settlementRepo.findByTradeId(1L) } returns null
        assertThat(service.getByTradeId(99L, 1L)).isNull()
    }

    private fun makeTrade(side: String, price: BigDecimal, qty: Int, tradedAt: java.time.Instant = java.time.Instant.now()) = PaperTrade(
        id = 1L, userId = 10L, stockId = 100L,
        side = side, quantity = qty, price = price,
        amount = price.multiply(BigDecimal(qty)),
        tradedAt = tradedAt,
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
