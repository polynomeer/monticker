package com.monticker.api.wallet.application

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ReplayServiceTest {

    private val ledgerService = mockk<LedgerService>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = ReplayService(ledgerService, jdbc)

    private val date = LocalDate.of(2026, 6, 30)

    private fun ledgerEvent(
        eventType: String,
        amount: BigDecimal,
        paperTradeId: Long? = null,
        stockId: Long? = null,
    ) = LedgerEventDto(
        id = 1L, eventType = eventType, amount = amount, balanceAfter = null,
        paperTradeId = paperTradeId, stockId = stockId, description = null, createdAt = Instant.now(),
    )

    @Test
    fun `FILL events are mapped to type BUY`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-100000"))
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events).hasSize(1)
        assertThat(result.events[0].type).isEqualTo("BUY")
    }

    @Test
    fun `SETTLEMENT events are mapped to type SELL`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("SETTLEMENT", BigDecimal("100000"))
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events[0].type).isEqualTo("SELL")
    }

    @Test
    fun `DEPOSIT and WITHDRAWAL events pass through with their own type`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("DEPOSIT", BigDecimal("500000")),
            ledgerEvent("WITHDRAWAL", BigDecimal("-200000")),
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events.map { it.type }).containsExactly("DEPOSIT", "WITHDRAWAL")
    }

    @Test
    fun `unrecognised event types are filtered out of the replay`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("CASH_RESERVED", BigDecimal("-50000")),
            ledgerEvent("FILL", BigDecimal("-10000")),
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events).hasSize(1)
        assertThat(result.events[0].type).isEqualTo("BUY")
    }

    @Test
    fun `FILL event with trade and stock info populates symbol qty and price`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-700000"), paperTradeId = 10L, stockId = 100L)
        )
        every { jdbc.queryForMap("SELECT symbol FROM stocks WHERE id = ?", 100L) } returns mapOf("symbol" to "005930")
        every {
            jdbc.queryForMap("SELECT quantity, price FROM paper_trades WHERE id = ?", 10L)
        } returns mapOf("quantity" to 10, "price" to BigDecimal("70000"))

        val result = service.getDailyReplay(1L, date)

        val event = result.events[0]
        assertThat(event.stockSymbol).isEqualTo("005930")
        assertThat(event.qty).isEqualTo(10)
        assertThat(event.price).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(event.pnlPct).isNull() // BUY events never compute pnl
    }

    @Test
    fun `SELL event computes pnlPct against the average buy price`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("SETTLEMENT", BigDecimal("770000"), paperTradeId = 11L, stockId = 100L)
        )
        every { jdbc.queryForMap("SELECT symbol FROM stocks WHERE id = ?", 100L) } returns mapOf("symbol" to "005930")
        every {
            jdbc.queryForMap("SELECT quantity, price FROM paper_trades WHERE id = ?", 11L)
        } returns mapOf("quantity" to 10, "price" to BigDecimal("77000"))
        every {
            jdbc.queryForObject(
                "SELECT AVG(price) FROM paper_trades WHERE user_id=? AND stock_id=? AND side='BUY'",
                BigDecimal::class.java, 1L, 100L,
            )
        } returns BigDecimal("70000")

        val result = service.getDailyReplay(1L, date)

        // (77000 - 70000) / 70000 * 100 = 10%
        assertThat(result.events[0].pnlPct).isNotNull()
        assertThat(result.events[0].pnlPct!!).isCloseTo(10.0, within(0.01))
    }

    @Test
    fun `SELL event leaves pnlPct null when no prior buy trades exist for the stock`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("SETTLEMENT", BigDecimal("770000"), paperTradeId = 11L, stockId = 100L)
        )
        every { jdbc.queryForMap("SELECT symbol FROM stocks WHERE id = ?", 100L) } returns mapOf("symbol" to "005930")
        every {
            jdbc.queryForMap("SELECT quantity, price FROM paper_trades WHERE id = ?", 11L)
        } returns mapOf("quantity" to 10, "price" to BigDecimal("77000"))
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, any(), any())
        } throws org.springframework.dao.EmptyResultDataAccessException(1)

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events[0].pnlPct).isNull()
    }

    @Test
    fun `summary totalPnl is settlement proceeds minus absolute fill cost`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-500000")),
            ledgerEvent("SETTLEMENT", BigDecimal("550000")),
        )

        val result = service.getDailyReplay(1L, date)

        // 550000 - |-500000| = 50000
        assertThat(result.summary.totalPnl).isEqualByComparingTo(BigDecimal("50000"))
    }

    @Test
    fun `summary tradeCount counts only BUY and SELL events not deposits or withdrawals`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-100000")),
            ledgerEvent("SETTLEMENT", BigDecimal("100000")),
            ledgerEvent("DEPOSIT", BigDecimal("500000")),
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.summary.tradeCount).isEqualTo(2)
    }

    @Test
    fun `summary picks best and worst trade by pnlPct`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("SETTLEMENT", BigDecimal("110000"), paperTradeId = 1L, stockId = 100L),
            ledgerEvent("SETTLEMENT", BigDecimal("90000"), paperTradeId = 2L, stockId = 200L),
        )
        every { jdbc.queryForMap("SELECT symbol FROM stocks WHERE id = ?", 100L) } returns mapOf("symbol" to "AAA")
        every { jdbc.queryForMap("SELECT symbol FROM stocks WHERE id = ?", 200L) } returns mapOf("symbol" to "BBB")
        every {
            jdbc.queryForMap("SELECT quantity, price FROM paper_trades WHERE id = ?", 1L)
        } returns mapOf("quantity" to 1, "price" to BigDecimal("110"))
        every {
            jdbc.queryForMap("SELECT quantity, price FROM paper_trades WHERE id = ?", 2L)
        } returns mapOf("quantity" to 1, "price" to BigDecimal("90"))
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, 1L, 100L)
        } returns BigDecimal("100") // +10%
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, 1L, 200L)
        } returns BigDecimal("100") // -10%

        val result = service.getDailyReplay(1L, date)

        assertThat(result.summary.bestTrade?.stockSymbol).isEqualTo("AAA")
        assertThat(result.summary.worstTrade?.stockSymbol).isEqualTo("BBB")
    }

    @Test
    fun `returns an empty replay when there are no ledger events for the date`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns emptyList()

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events).isEmpty()
        assertThat(result.summary.tradeCount).isEqualTo(0)
        assertThat(result.summary.bestTrade).isNull()
        assertThat(result.summary.worstTrade).isNull()
    }
}
