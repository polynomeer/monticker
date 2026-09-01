package com.monticker.api.wallet.application

import com.monticker.api.common.domain.Money
import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Optional

class WalletServiceTest {

    private val accountRepo = mockk<PaperAccountRepository>()
    private val ledgerService = mockk<LedgerService>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = WalletService(accountRepo, ledgerService, jdbc)

    @Test
    fun `getWalletMap sums available cash and holdings value into total assets`() {
        val account = PaperAccount(userId = 1L, cash = Money.of("5000000"))
        every { accountRepo.findByUserId(1L) } returns Optional.of(account)
        every {
            jdbc.queryForList(match<String> { it.contains("GROUP BY stock_id") }, any<Long>())
        } returns listOf(mapOf("stock_id" to 100L, "net_qty" to 10))
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L)
        } returns BigDecimal("60000")
        every { ledgerService.getLedger(1L) } returns emptyList()

        val result = service.getWalletMap(1L)

        // holdings value = 10 * 60000 = 600,000 ; total = 5,000,000 + 600,000
        assertThat(result.availableCash).isEqualByComparingTo(BigDecimal("5000000"))
        assertThat(result.holdingsValue).isEqualByComparingTo(BigDecimal("600000"))
        assertThat(result.totalAssets).isEqualByComparingTo(BigDecimal("5600000"))
    }

    @Test
    fun `getWalletMap reports zero reserved cash and zero settlement pending in the mock environment`() {
        val account = PaperAccount(userId = 1L, cash = Money.of("1000000"))
        every { accountRepo.findByUserId(1L) } returns Optional.of(account)
        every { jdbc.queryForList(any<String>(), any<Long>()) } returns emptyList()
        every { ledgerService.getLedger(1L) } returns emptyList()

        val result = service.getWalletMap(1L)

        assertThat(result.reservedCash).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.settlementPending).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `getWalletMap creates a default account when the user has none yet`() {
        every { accountRepo.findByUserId(2L) } returns Optional.empty()
        every { jdbc.queryForList(any<String>(), any<Long>()) } returns emptyList()
        every { ledgerService.getLedger(2L) } returns emptyList()

        val result = service.getWalletMap(2L)

        // PaperAccount's default cash is 10,000,000
        assertThat(result.availableCash).isEqualByComparingTo(BigDecimal("10000000"))
    }

    @Test
    fun `getWalletMap returns only the most recent 10 ledger events`() {
        val account = PaperAccount(userId = 1L, cash = Money.of("1000000"))
        every { accountRepo.findByUserId(1L) } returns Optional.of(account)
        every { jdbc.queryForList(any<String>(), any<Long>()) } returns emptyList()
        val manyEvents = (1..15).map {
            LedgerEventDto(
                id = it.toLong(), eventType = "FILL", amount = BigDecimal.ONE, balanceAfter = null,
                paperTradeId = null, stockId = null, description = null, createdAt = java.time.Instant.now(),
            )
        }
        every { ledgerService.getLedger(1L) } returns manyEvents

        val result = service.getWalletMap(1L)

        assertThat(result.recentLedger).hasSize(10)
    }
}
