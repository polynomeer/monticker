package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.paper.application.PaperTradeSummary
import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.time.Instant

class LedgerServiceTest {

    private val ledgerRepo = mockk<LedgerEventRepository>()
    private val tradeQueryService = mockk<PaperTradeQueryService>(relaxed = true)
    private val service = LedgerService(ledgerRepo, tradeQueryService)

    @Test
    fun `recordBuy stores a negative amount FILL event`() {
        val slot = slot<LedgerEvent>()
        every { ledgerRepo.existsByPaperTradeIdAndEventType(any(), any()) } returns false
        every { ledgerRepo.save(capture(slot)) } answers { slot.captured }

        service.recordBuy(userId = 1L, tradeId = 10L, stockId = 100L, amount = BigDecimal("50000"), balanceAfter = BigDecimal("950000"))

        assertThat(slot.captured.eventType).isEqualTo(LedgerEventType.FILL)
        assertThat(slot.captured.amount).isEqualByComparingTo(BigDecimal("-50000"))
        assertThat(slot.captured.balanceAfter).isEqualByComparingTo(BigDecimal("950000"))
        assertThat(slot.captured.paperTradeId).isEqualTo(10L)
        assertThat(slot.captured.stockId).isEqualTo(100L)
        assertThat(slot.captured.description).contains("매수")
    }

    @Test
    fun `recordSell stores a positive amount SETTLEMENT event`() {
        val slot = slot<LedgerEvent>()
        every { ledgerRepo.existsByPaperTradeIdAndEventType(any(), any()) } returns false
        every { ledgerRepo.save(capture(slot)) } answers { slot.captured }

        service.recordSell(userId = 1L, tradeId = 11L, stockId = 100L, amount = BigDecimal("60000"), balanceAfter = BigDecimal("1010000"))

        assertThat(slot.captured.eventType).isEqualTo(LedgerEventType.SETTLEMENT)
        assertThat(slot.captured.amount).isEqualByComparingTo(BigDecimal("60000"))
        assertThat(slot.captured.description).contains("매도")
    }

    @Test
    fun `recordDeposit stores a DEPOSIT event with no associated trade or stock`() {
        val slot = slot<LedgerEvent>()
        every { ledgerRepo.save(capture(slot)) } answers { slot.captured }

        service.recordDeposit(userId = 1L, amount = BigDecimal("100000"), balanceAfter = BigDecimal("1100000"))

        assertThat(slot.captured.eventType).isEqualTo(LedgerEventType.DEPOSIT)
        assertThat(slot.captured.amount).isEqualByComparingTo(BigDecimal("100000"))
        assertThat(slot.captured.paperTradeId).isNull()
        assertThat(slot.captured.stockId).isNull()
    }

    // ADR-043 — 초기화는 (초기 잔고 − 직전 잔고)를 실현된 이동으로 남긴다. 부호에 따라 DEPOSIT/WITHDRAWAL.
    @Test
    fun `recordReset writes a DEPOSIT for the top-up back to the initial balance`() {
        val slot = slot<LedgerEvent>()
        every { ledgerRepo.existsByDedupKey(any()) } returns false
        every { ledgerRepo.save(capture(slot)) } answers { slot.captured }

        service.recordReset(userId = 1L, previousCash = BigDecimal("4000000"), newCash = BigDecimal("10000000"), eventId = "r1")

        assertThat(slot.captured.eventType).isEqualTo(LedgerEventType.DEPOSIT)
        assertThat(slot.captured.amount).isEqualByComparingTo(BigDecimal("6000000"))
        assertThat(slot.captured.balanceAfter).isEqualByComparingTo(BigDecimal("10000000"))
    }

    @Test
    fun `recordReset writes a WITHDRAWAL when the account was above the initial balance`() {
        val slot = slot<LedgerEvent>()
        every { ledgerRepo.existsByDedupKey(any()) } returns false
        every { ledgerRepo.save(capture(slot)) } answers { slot.captured }

        service.recordReset(userId = 1L, previousCash = BigDecimal("12000000"), newCash = BigDecimal("10000000"), eventId = "r2")

        assertThat(slot.captured.eventType).isEqualTo(LedgerEventType.WITHDRAWAL)
        assertThat(slot.captured.amount).isEqualByComparingTo(BigDecimal("-2000000"))
    }

    @Test
    fun `recordReset writes nothing when the balance did not change`() {
        service.recordReset(userId = 1L, previousCash = BigDecimal("10000000"), newCash = BigDecimal("10000000"), eventId = "r3")

        verify(exactly = 0) { ledgerRepo.save(any()) }
    }

    private fun event(id: Long, userId: Long = 1L) = LedgerEvent(
        id = id, userId = userId, eventType = LedgerEventType.FILL,
        amount = BigDecimal("-1000"), balanceAfter = BigDecimal("9000"),
        paperTradeId = 5L, stockId = 10L, description = "매수 체결",
    )

    @Test
    fun `getLedger maps repository entities to DTOs preserving field values`() {
        every { ledgerRepo.findPage(1L, Long.MAX_VALUE, any()) } returns listOf(event(1L))

        val result = service.getLedger(1L)

        assertThat(result.items).hasSize(1)
        assertThat(result.items[0].eventType).isEqualTo("FILL")
        assertThat(result.items[0].amount).isEqualByComparingTo(BigDecimal("-1000"))
        assertThat(result.items[0].paperTradeId).isEqualTo(5L)
        assertThat(result.nextCursor).isNull()
    }

    @Test
    fun `getLedger returns an empty page when the user has no events`() {
        every { ledgerRepo.findPage(2L, Long.MAX_VALUE, any()) } returns emptyList()

        val result = service.getLedger(2L)

        assertThat(result.items).isEmpty()
        assertThat(result.nextCursor).isNull()
    }

    // ADR-043 — 커서 페이징. limit+1건을 읽어 다음 페이지 유무를 정확히 판정한다.
    @Test
    fun `getLedger reads limit plus one rows and exposes the last returned id as the next cursor`() {
        val pageableSlot = slot<Pageable>()
        every { ledgerRepo.findPage(1L, Long.MAX_VALUE, capture(pageableSlot)) } returns
            listOf(event(30L), event(29L), event(28L))   // limit=2 → 3건 반환 = 다음 페이지 있음

        val result = service.getLedger(1L, cursor = null, limit = 2)

        assertThat(pageableSlot.captured.pageSize).isEqualTo(3)
        assertThat(result.items.map { it.id }).containsExactly(30L, 29L)
        assertThat(result.nextCursor).isEqualTo(29L)
    }

    @Test
    fun `getLedger with a cursor returns null nextCursor on the exactly-full last page`() {
        every { ledgerRepo.findPage(1L, 29L, any()) } returns listOf(event(28L), event(27L))   // limit=2, 정확히 2건

        val result = service.getLedger(1L, cursor = 29L, limit = 2)

        assertThat(result.items.map { it.id }).containsExactly(28L, 27L)
        assertThat(result.nextCursor).isNull()
    }

    @Test
    fun `getLedger clamps limit into 1 to MAX_PAGE`() {
        val sizes = mutableListOf<Pageable>()
        every { ledgerRepo.findPage(1L, Long.MAX_VALUE, capture(sizes)) } returns emptyList()

        service.getLedger(1L, limit = 0)
        service.getLedger(1L, limit = 10_000)

        assertThat(sizes.map { it.pageSize }).containsExactly(2, LedgerService.MAX_PAGE + 1)
    }

    @Test
    fun `getRecentLedger asks the database for exactly n rows`() {
        val pageableSlot = slot<Pageable>()
        every { ledgerRepo.findPage(1L, Long.MAX_VALUE, capture(pageableSlot)) } returns listOf(event(1L))

        val result = service.getRecentLedger(1L, 10)

        assertThat(pageableSlot.captured.pageSize).isEqualTo(10)
        assertThat(result).hasSize(1)
    }

    @Test
    fun `getLedgerForDate queries the repository with a KST day boundary range`() {
        val rangeSlot = mutableListOf<Instant>()
        every {
            ledgerRepo.findAllByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(1L, capture(rangeSlot), capture(rangeSlot))
        } returns emptyList()

        val date = java.time.LocalDate.of(2026, 6, 30)
        service.getLedgerForDate(1L, date)

        assertThat(rangeSlot).hasSize(2)
        val from = rangeSlot[0]
        val to = rangeSlot[1]
        assertThat(to).isAfter(from)
        // exactly 24 hours apart (one calendar day)
        assertThat(java.time.Duration.between(from, to)).isEqualTo(java.time.Duration.ofDays(1))
    }

    @Test
    fun `getLedgerForDate maps results to DTOs`() {
        val event = LedgerEvent(
            id = 2L, userId = 1L, eventType = LedgerEventType.DEPOSIT,
            amount = BigDecimal("100000"), description = "입금",
        )
        every {
            ledgerRepo.findAllByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(any(), any(), any())
        } returns listOf(event)

        val result = service.getLedgerForDate(1L, java.time.LocalDate.now())

        assertThat(result).hasSize(1)
        assertThat(result[0].eventType).isEqualTo("DEPOSIT")
    }

    @Test
    fun `recordBuy is idempotent — skips save when FILL already exists for the trade`() {
        every { ledgerRepo.existsByPaperTradeIdAndEventType(10L, LedgerEventType.FILL) } returns true
        service.recordBuy(userId = 1L, tradeId = 10L, stockId = 100L, amount = BigDecimal("50000"), balanceAfter = BigDecimal("950000"))
        verify(exactly = 0) { ledgerRepo.save(any()) }
    }

    @Test
    fun `recordSell is idempotent — skips save when SETTLEMENT already exists for the trade`() {
        every { ledgerRepo.existsByPaperTradeIdAndEventType(11L, LedgerEventType.SETTLEMENT) } returns true
        service.recordSell(userId = 1L, tradeId = 11L, stockId = 100L, amount = BigDecimal("60000"), balanceAfter = BigDecimal("1010000"))
        verify(exactly = 0) { ledgerRepo.save(any()) }
    }

    @Test
    fun `recordReset is idempotent by eventId`() {
        every { ledgerRepo.existsByDedupKey("RESET:dup") } returns true
        service.recordReset(userId = 1L, previousCash = BigDecimal("4000000"), newCash = BigDecimal("10000000"), eventId = "dup")
        verify(exactly = 0) { ledgerRepo.save(any()) }
    }

    @Test
    fun `recordSettlementComplete is idempotent by settlementId`() {
        every { ledgerRepo.existsByDedupKey("SETTLE:77") } returns true
        service.recordSettlementComplete(userId = 1L, settlementId = 77L, stockId = 100L,
            fee = BigDecimal("10"), tax = BigDecimal("5"), balanceAfter = BigDecimal("999985"))
        verify(exactly = 0) { ledgerRepo.save(any()) }
    }

    // ADR-085 — 원장 행의 주문 출처는 거래를 한 번에 읽어 붙인다
    @Test
    fun `getLedger attaches the trade origin to fill rows in one batch lookup`() {
        every { ledgerRepo.findPage(1L, Long.MAX_VALUE, any()) } returns listOf(
            event(1L),
            LedgerEvent(id = 2L, userId = 1L, eventType = LedgerEventType.DEPOSIT, amount = BigDecimal("100")),
        )
        every { tradeQueryService.findOwnedByIds(1L, listOf(5L)) } returns listOf(
            PaperTradeSummary(5L, 1L, 10L, "BUY", 1, BigDecimal("1000"), BigDecimal("1000"), origin = "WATCH_RULE", originRef = 3L),
        )

        val items = service.getLedger(1L).items

        assertThat(items[0].origin).isEqualTo("WATCH_RULE")
        assertThat(items[0].originRef).isEqualTo(3L)
        assertThat(items[1].origin).isNull()
        verify(exactly = 1) { tradeQueryService.findOwnedByIds(1L, any()) }
    }

    @Test
    fun `getLedger does not attach an origin when the linked trade is for a different stock`() {
        // V43 — ADR-047 이전 원장은 paper_trade_id에 fills.id를 담아 다른 거래와 id가 겹칠 수 있다
        every { ledgerRepo.findPage(1L, Long.MAX_VALUE, any()) } returns listOf(event(1L))
        every { tradeQueryService.findOwnedByIds(1L, listOf(5L)) } returns listOf(
            PaperTradeSummary(5L, 1L, 999L, "BUY", 1, BigDecimal("1000"), BigDecimal("1000"), origin = "MANUAL"),
        )

        assertThat(service.getLedger(1L).items[0].origin).isNull()
    }
}
