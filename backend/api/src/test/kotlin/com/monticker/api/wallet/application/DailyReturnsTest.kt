package com.monticker.api.wallet.application

import com.monticker.api.common.time.KstPeriod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** ADR-091 일별 수익률 = (끝 − 시작 − 입출금) ÷ 시작 평가자산. SQL·경계 조립은 DailyReturnIntegrationTest. */
class DailyReturnsTest {

    private val days = listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7))
    private val points = days.map { KstPeriod.startOf(it) } + KstPeriod.startOf(LocalDate.of(2026, 10, 8))
    private val created = Instant.parse("2026-01-01T00:00:00Z")
    private fun bd(v: String) = BigDecimal(v)
    private val zero = listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

    @Test
    fun `return is pnl over start-of-day equity, with deposits and withdrawals taken out of pnl`() {
        val r = DailyReturns.compute(
            days, points,
            equity = listOf(bd("10000000"), bd("10100000"), bd("11100000"), bd("10989000")),
            flows = listOf(BigDecimal.ZERO, bd("1000000"), BigDecimal.ZERO),
            accountCreatedAt = created, lastResetAt = null,
        )
        assertThat(r.map { it.status }).containsOnly(DailyReturnStatus.OK)
        assertThat(r[0].returnPct!!).isCloseTo(1.0, within(1e-9))
        // 입금 100만은 손익이 아니다 → 0%
        assertThat(r[1].pnl).isEqualByComparingTo("0")
        assertThat(r[1].returnPct!!).isCloseTo(0.0, within(1e-9))
        assertThat(r[2].returnPct!!).isCloseTo(-1.0, within(1e-9))
    }

    @Test
    fun `days before the account existed, before the latest reset, or without a price are not computed`() {
        val createdOn6th = KstPeriod.startOf(LocalDate.of(2026, 10, 6)).plusSeconds(3600)
        val r = DailyReturns.compute(
            days, points, equity = listOf(bd("1"), bd("1"), null, bd("1")), flows = zero,
            accountCreatedAt = createdOn6th, lastResetAt = null,
        )
        assertThat(r.map { it.status }).containsExactly(DailyReturnStatus.NO_ACCOUNT, DailyReturnStatus.NO_PRICE, DailyReturnStatus.NO_PRICE)
        assertThat(r.map { it.returnPct }).containsOnlyNulls()

        // 초기화가 7일 장중에 있었다 → 7일과 그 이전은 체결 기록이 지워져 계산하지 않는다
        val reset = KstPeriod.startOf(LocalDate.of(2026, 10, 7)).plusSeconds(60)
        val r2 = DailyReturns.compute(
            days, points, equity = listOf(bd("1"), bd("1"), bd("1"), bd("1")), flows = zero,
            accountCreatedAt = created, lastResetAt = reset,
        )
        assertThat(r2.map { it.status }).containsOnly(DailyReturnStatus.RESET)
    }

    @Test
    fun `a reset exactly at the day boundary leaves that day computable`() {
        val reset = KstPeriod.startOf(LocalDate.of(2026, 10, 7))
        val r = DailyReturns.compute(
            days, points, equity = listOf(bd("1"), bd("1"), bd("100"), bd("101")), flows = zero,
            accountCreatedAt = created, lastResetAt = reset,
        )
        assertThat(r.map { it.status }).containsExactly(DailyReturnStatus.RESET, DailyReturnStatus.RESET, DailyReturnStatus.OK)
        assertThat(r[2].returnPct!!).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `zero start equity has no return instead of dividing by zero`() {
        val r = DailyReturns.compute(
            days.take(1), points.take(2), equity = listOf(BigDecimal.ZERO, bd("5")), flows = listOf(BigDecimal.ZERO),
            accountCreatedAt = created, lastResetAt = null,
        )
        assertThat(r.single().status).isEqualTo(DailyReturnStatus.NO_EQUITY)
        assertThat(r.single().returnPct).isNull()
    }
}
