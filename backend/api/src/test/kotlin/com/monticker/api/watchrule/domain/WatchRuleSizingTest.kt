package com.monticker.api.watchrule.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** ADR-095 — 지정가 오프셋과 계좌 % 수량 계산. */
class WatchRuleSizingTest {

    @Test
    fun `limit price applies the offset and rounds away from aggression`() {
        // 71,234 × 0.99 = 70,521.66 → 매수는 내림(70,521), 매도는 올림(70,522)
        assertThat(WatchRuleSizing.limitPrice(BigDecimal("71234"), -100, WatchRuleSide.BUY)).isEqualByComparingTo("70521")
        assertThat(WatchRuleSizing.limitPrice(BigDecimal("71234"), -100, WatchRuleSide.SELL)).isEqualByComparingTo("70522")
        assertThat(WatchRuleSizing.limitPrice(BigDecimal("70000"), 0, WatchRuleSide.BUY)).isEqualByComparingTo("70000")
        assertThat(WatchRuleSizing.limitPrice(BigDecimal("70000"), 1000, WatchRuleSide.BUY)).isEqualByComparingTo("77000")
    }

    @Test
    fun `limit price keeps the reference price's decimals up to four`() {
        assertThat(WatchRuleSizing.limitPrice(BigDecimal("182.35"), 50, WatchRuleSide.BUY)).isEqualByComparingTo("183.26")   // 183.26175
        assertThat(WatchRuleSizing.limitPrice(BigDecimal("1.23456"), 0, WatchRuleSide.SELL).scale()).isLessThanOrEqualTo(4)
    }

    @Test
    fun `limit offset outside plus or minus 1000 bps is rejected`() {
        assertThatThrownBy { WatchRuleSizing.limitPrice(BigDecimal("1000"), 1001, WatchRuleSide.BUY) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WatchRuleSizing.limitPrice(BigDecimal("1000"), -1001, WatchRuleSide.BUY) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WatchRuleSizing.limitPrice(BigDecimal.ZERO, 0, WatchRuleSide.BUY) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `equity percent quantity floors to whole shares`() {
        // 10,000,000 × 5% = 500,000 ÷ 70,000 = 7.14 → 7주
        assertThat(WatchRuleSizing.quantityForEquity(BigDecimal("10000000"), BigDecimal("5"), BigDecimal("70000"))).isEqualTo(7)
        // 정확히 나눠떨어지면 그 수
        assertThat(WatchRuleSizing.quantityForEquity(BigDecimal("1000000"), BigDecimal("10"), BigDecimal("50000"))).isEqualTo(2)
        // 소수 비율
        assertThat(WatchRuleSizing.quantityForEquity(BigDecimal("1000000"), BigDecimal("2.5"), BigDecimal("1000"))).isEqualTo(25)
    }

    @Test
    fun `equity percent that cannot buy one share rounds to zero`() {
        // 1,000,000 × 1% = 10,000 < 1주 70,000
        assertThat(WatchRuleSizing.quantityForEquity(BigDecimal("1000000"), BigDecimal("1"), BigDecimal("70000"))).isZero()
        assertThat(WatchRuleSizing.quantityForEquity(BigDecimal.ZERO, BigDecimal("25"), BigDecimal("100"))).isZero()
        assertThat(WatchRuleSizing.quantityForEquity(BigDecimal("-5"), BigDecimal("25"), BigDecimal("100"))).isZero()
    }

    @Test
    fun `equity percent bounds`() {
        WatchRuleSizing.requireEquityPct(BigDecimal("1"))
        WatchRuleSizing.requireEquityPct(BigDecimal("25"))
        WatchRuleSizing.requireEquityPct(BigDecimal("12.5"))
        listOf("0.5", "25.5", "-1").forEach { pct ->
            assertThatThrownBy { WatchRuleSizing.requireEquityPct(BigDecimal(pct)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { WatchRuleSizing.requireEquityPct(BigDecimal("5.125")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
