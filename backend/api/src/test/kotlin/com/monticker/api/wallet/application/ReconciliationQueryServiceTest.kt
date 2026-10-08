package com.monticker.api.wallet.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** GET /api/wallet/reconciliation — 스냅샷만 읽고, 드리프트는 대사와 같은 식으로 계산한다. */
class ReconciliationQueryServiceTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val service = ReconciliationQueryService(jdbc)

    private fun row(cash: String, reserved: String, ledger: String, mismatch: Boolean): ResultSet = mockk {
        every { getBigDecimal("account_cash") } returns BigDecimal(cash)
        every { getBigDecimal("reserved_cash") } returns BigDecimal(reserved)
        every { getBigDecimal("ledger_sum") } returns BigDecimal(ledger)
        every { getDate("as_of_date") } returns java.sql.Date.valueOf(LocalDate.of(2026, 10, 2))
        every { getBoolean("mismatch") } returns mismatch
        every { getTimestamp("created_at") } returns Timestamp.from(Instant.EPOCH)
    }

    private fun initialCapital(amount: String?) {
        every { jdbc.query(match<String> { it.contains("initial_capital") }, any<RowMapper<BigDecimal>>(), 1L) } returns
            listOfNotNull(amount?.let(::BigDecimal))
    }

    @Test
    fun `counts mismatch days and computes the drift of the latest snapshot`() {
        // 초기 10,000,000 + 원장 −500,000 = 9,500,000 ; 잔고 9,400,000 + 예약 100,000 = 9,500,000 → 드리프트 0
        initialCapital(null)   // 계좌 행이 없으면 1,000만
        every { jdbc.query(match<String> { it.contains("LIMIT 1") }, any<RowMapper<ReconciliationDay>>(), 1L) } answers {
            listOf(secondArg<RowMapper<ReconciliationDay>>().mapRow(row("9400000", "100000", "-500000", false), 0)!!)
        }
        every { jdbc.query(match<String> { it.contains("AND mismatch") }, any<RowMapper<ReconciliationDay>>(), 1L, any(), 90) } answers {
            listOf(secondArg<RowMapper<ReconciliationDay>>().mapRow(row("9400000", "0", "-500000", true), 0)!!)
        }

        val s = service.summary(1L)

        assertThat(s.mismatchCount).isEqualTo(1)
        assertThat(s.latest!!.drift).isEqualByComparingTo("0")
        assertThat(s.mismatches.single().drift).isEqualByComparingTo("-100000")
        // 읽기만 한다 — 스냅샷을 쓰지 않는다
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `uses the account's chosen initial capital for the drift (ADR-089)`() {
        // 시작 자금 3,000만 + 원장 −500,000 = 29,500,000 ; 잔고 29,500,000 → 드리프트 0 (1,000만 상수였다면 +2,000만으로 보였다)
        initialCapital("30000000")
        every { jdbc.query(match<String> { it.contains("LIMIT 1") }, any<RowMapper<ReconciliationDay>>(), 1L) } answers {
            listOf(secondArg<RowMapper<ReconciliationDay>>().mapRow(row("29500000", "0", "-500000", false), 0)!!)
        }
        every { jdbc.query(match<String> { it.contains("AND mismatch") }, any<RowMapper<ReconciliationDay>>(), 1L, any(), 90) } returns emptyList()

        assertThat(service.summary(1L).latest!!.drift).isEqualByComparingTo("0")
    }
}
