package com.monticker.api.wallet.application

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
    private val service = LedgerService(ledgerRepo)

    @Test
    fun `recordBuy stores a negative amount FILL event`() {
        val slot = slot<LedgerEvent>()
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
}
