package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.paper.application.PaperTradeSummary
import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class ReceiptServiceTest {

    private val tradeQueryService = mockk<PaperTradeQueryService>()
    private val ledgerRepo = mockk<LedgerEventRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = ReceiptService(tradeQueryService, ledgerRepo, jdbc)

    @Test
    fun `getReceipt computes fee as 0_015 percent of the trade amount`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 3, price = BigDecimal("70000"), amount = BigDecimal("210000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        every { ledgerRepo.findAll() } returns emptyList()

        val receipt = service.getReceipt(1L, 1L)

        // 210000 * 0.00015 = 31.5 -> rounds to 32 (HALF_UP) — verify exact rounding behavior
        assertThat(receipt.fee).isEqualByComparingTo(BigDecimal("32"))
    }

    @Test
    fun `getReceipt adds fee on top of amount for a BUY (settled amount is the total cash outflow)`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        every { ledgerRepo.findAll() } returns emptyList()

        val receipt = service.getReceipt(1L, 1L)

        // fee = 100000 * 0.00015 = 15
        assertThat(receipt.fee).isEqualByComparingTo(BigDecimal("15"))
        assertThat(receipt.settledAmount).isEqualByComparingTo(BigDecimal("100015"))
    }

    @Test
    fun `getReceipt subtracts fee from amount for a SELL (settled amount is the net proceeds)`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "SELL", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        every { ledgerRepo.findAll() } returns emptyList()

        val receipt = service.getReceipt(1L, 1L)

        assertThat(receipt.settledAmount).isEqualByComparingTo(BigDecimal("99985"))
    }

    @Test
    fun `getReceipt resolves balanceBefore from the matching ledger entry for a BUY`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        val ledgerEntry = LedgerEvent(
            id = 1L, userId = 1L, eventType = LedgerEventType.FILL,
            amount = BigDecimal("-100000"), balanceAfter = BigDecimal("900000"), paperTradeId = 1L,
        )
        every { ledgerRepo.findAll() } returns listOf(ledgerEntry)

        val receipt = service.getReceipt(1L, 1L)

        assertThat(receipt.balanceAfter).isEqualByComparingTo(BigDecimal("900000"))
        // BUY: balanceBefore = balanceAfter + amount = 900000 + 100000
        assertThat(receipt.balanceBefore).isEqualByComparingTo(BigDecimal("1000000"))
    }

    @Test
    fun `getReceipt resolves balanceBefore from the matching ledger entry for a SELL`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "SELL", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        val ledgerEntry = LedgerEvent(
            id = 1L, userId = 1L, eventType = LedgerEventType.SETTLEMENT,
            amount = BigDecimal("100000"), balanceAfter = BigDecimal("1100000"), paperTradeId = 1L,
        )
        every { ledgerRepo.findAll() } returns listOf(ledgerEntry)

        val receipt = service.getReceipt(1L, 1L)

        // SELL: balanceBefore = balanceAfter - amount = 1100000 - 100000
        assertThat(receipt.balanceBefore).isEqualByComparingTo(BigDecimal("1000000"))
    }

    @Test
    fun `getReceipt leaves balance fields null when no ledger entry is found for the trade`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        every { ledgerRepo.findAll() } returns emptyList()

        val receipt = service.getReceipt(1L, 1L)

        assertThat(receipt.balanceBefore).isNull()
        assertThat(receipt.balanceAfter).isNull()
    }

    @Test
    fun `getReceipt picks the most recent ledger entry when multiple exist for the same trade`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        val older = LedgerEvent(
            id = 1L, userId = 1L, eventType = LedgerEventType.FILL, amount = BigDecimal("-100000"),
            balanceAfter = BigDecimal("950000"), paperTradeId = 1L,
            createdAt = java.time.Instant.now().minusSeconds(60),
        )
        val newer = LedgerEvent(
            id = 2L, userId = 1L, eventType = LedgerEventType.FILL, amount = BigDecimal("-100000"),
            balanceAfter = BigDecimal("900000"), paperTradeId = 1L,
            createdAt = java.time.Instant.now(),
        )
        every { ledgerRepo.findAll() } returns listOf(older, newer)

        val receipt = service.getReceipt(1L, 1L)

        assertThat(receipt.balanceAfter).isEqualByComparingTo(BigDecimal("900000"))
    }

    @Test
    fun `getReceipt throws when the trade does not exist`() {
        every { tradeQueryService.getById(99L) } throws NoSuchElementException("Paper trade not found: 99")

        assertThatThrownBy { service.getReceipt(1L, 99L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `getReceipt throws when the trade belongs to a different user`() {
        val trade = PaperTradeSummary(id = 1L, userId = 999L, stockId = 100L, side = "BUY", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade

        assertThatThrownBy { service.getReceipt(1L, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `getReceipt always reports status SETTLED in the mock environment`() {
        val trade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"), tradedAt = java.time.Instant.now())
        every { tradeQueryService.getById(1L) } returns trade
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        every { ledgerRepo.findAll() } returns emptyList()

        val receipt = service.getReceipt(1L, 1L)

        assertThat(receipt.status).isEqualTo("SETTLED")
    }
}
