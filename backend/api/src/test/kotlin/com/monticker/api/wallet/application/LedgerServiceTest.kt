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

    @Test
    fun `getLedger maps repository entities to DTOs preserving field values`() {
        val event = LedgerEvent(
            id = 1L, userId = 1L, eventType = LedgerEventType.FILL,
            amount = BigDecimal("-1000"), balanceAfter = BigDecimal("9000"),
            paperTradeId = 5L, stockId = 10L, description = "매수 체결",
        )
        every { ledgerRepo.findAllByUserIdOrderByCreatedAtDesc(1L) } returns listOf(event)

        val result = service.getLedger(1L)

        assertThat(result).hasSize(1)
        assertThat(result[0].eventType).isEqualTo("FILL")
        assertThat(result[0].amount).isEqualByComparingTo(BigDecimal("-1000"))
        assertThat(result[0].paperTradeId).isEqualTo(5L)
    }

    @Test
    fun `getLedger returns an empty list when the user has no events`() {
        every { ledgerRepo.findAllByUserIdOrderByCreatedAtDesc(2L) } returns emptyList()

        val result = service.getLedger(2L)

        assertThat(result).isEmpty()
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
