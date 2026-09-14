package com.monticker.api.wallet.application

import com.monticker.api.common.domain.Money
import com.monticker.api.paper.application.PaperAccountQueryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class WalletServiceTest {

    private val accountQueryService = mockk<PaperAccountQueryService>()
    private val ledgerService = mockk<LedgerService>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = WalletService(accountQueryService, ledgerService, jdbc)

    // calcHoldingsValue는 portfolio_positions ↔ candles_1m LATERAL JOIN을 단일 집계 쿼리(SUM)로
    // DB에서 직접 계산한다 — 종목별 순회(queryForList + per-stock queryForObject)가 아니다.
    // 가격을 못 구한 보유분을 건너뛰는 동작(LATERAL JOIN이 자연히 제외)은 SQL 자체의 동작이라
    // 이 레벨의 mock 단위테스트로는 더 이상 검증할 수 없다 — Testcontainers 통합테스트가 필요.
    private fun stubHoldingsValue(userId: Long, value: BigDecimal) {
        every {
            jdbc.queryForObject(match<String> { it.contains("portfolio_positions") }, BigDecimal::class.java, userId)
        } returns value
    }

    // ADR-043 — 예약금은 미체결 BUY 주문의 limit_price × 잔량 (RESERVED_CASH_SQL)
    private fun stubReservedCash(userId: Long, value: BigDecimal) {
        every {
            jdbc.queryForObject(match<String> { it.contains("FROM orders") }, BigDecimal::class.java, userId)
        } returns value
    }

    @Test
    fun `getWalletMap sums available cash and holdings value into total assets`() {
        every { accountQueryService.getCashBalance(1L) } returns Money.of("5000000")
        stubHoldingsValue(1L, BigDecimal("600000"))
        stubReservedCash(1L, BigDecimal.ZERO)
        every { ledgerService.getRecentLedger(1L, 10) } returns emptyList()

        val result = service.getWalletMap(1L)

        assertThat(result.availableCash).isEqualByComparingTo(BigDecimal("5000000"))
        assertThat(result.holdingsValue).isEqualByComparingTo(BigDecimal("600000"))
        assertThat(result.totalAssets).isEqualByComparingTo(BigDecimal("5600000"))
    }

    @Test
    fun `getWalletMap reports reserved cash from open BUY orders and counts it in total assets`() {
        every { accountQueryService.getCashBalance(1L) } returns Money.of("1000000")
        stubHoldingsValue(1L, BigDecimal.ZERO)
        stubReservedCash(1L, BigDecimal("250000"))
        every { ledgerService.getRecentLedger(1L, 10) } returns emptyList()

        val result = service.getWalletMap(1L)

        assertThat(result.reservedCash).isEqualByComparingTo(BigDecimal("250000"))
        assertThat(result.totalAssets).isEqualByComparingTo(BigDecimal("1250000"))   // 예약금은 여전히 사용자 돈이다
        assertThat(result.settlementPending).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `getWalletMap creates a default account when the user has none yet`() {
        every { accountQueryService.getCashBalance(2L) } returns Money.INITIAL_BALANCE
        stubHoldingsValue(2L, BigDecimal.ZERO)
        stubReservedCash(2L, BigDecimal.ZERO)
        every { ledgerService.getRecentLedger(2L, 10) } returns emptyList()

        val result = service.getWalletMap(2L)

        // PaperAccount's default cash is 10,000,000
        assertThat(result.availableCash).isEqualByComparingTo(BigDecimal("10000000"))
    }

    @Test
    fun `getWalletMap asks the ledger for the 10 most recent events instead of slicing the whole ledger`() {
        every { accountQueryService.getCashBalance(1L) } returns Money.of("1000000")
        stubHoldingsValue(1L, BigDecimal.ZERO)
        stubReservedCash(1L, BigDecimal.ZERO)
        val ten = (1..10).map {
            LedgerEventDto(
                id = it.toLong(), eventType = "FILL", amount = BigDecimal.ONE, balanceAfter = null,
                paperTradeId = null, stockId = null, description = null, createdAt = java.time.Instant.now(),
            )
        }
        every { ledgerService.getRecentLedger(1L, 10) } returns ten

        val result = service.getWalletMap(1L)

        assertThat(result.recentLedger).hasSize(10)
        verify(exactly = 1) { ledgerService.getRecentLedger(1L, 10) }
    }
}
