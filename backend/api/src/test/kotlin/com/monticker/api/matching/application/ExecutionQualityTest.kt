package com.monticker.api.matching.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ExecutionQualityTest {

    private val d = LocalDate.of(2026, 10, 8)
    private val t0 = Instant.parse("2026-10-08T01:00:00Z")

    private fun row(
        orderId: Long, side: String, fill: String, bid: String? = "9990", ask: String? = "10010",
        qty: Int = 1, type: String = "MARKET", submitted: Instant? = t0, filledAt: Instant = t0.plusMillis(4),
    ) = ExecutionFillRow(orderId, side, type, qty, BigDecimal(fill), filledAt, bid?.let(::BigDecimal), ask?.let(::BigDecimal), submitted)

    @Test
    fun `slippage sign follows the side - positive is adverse, negative is price improvement`() {
        val bid = BigDecimal("9990")
        val ask = BigDecimal("10010")
        // 매수: ask보다 비싸게 → +, 싸게 → −
        assertThat(ExecutionQuality.slippageBps("BUY", BigDecimal("10020"), bid, ask)!!).isCloseTo(9.99, within(0.01))
        assertThat(ExecutionQuality.slippageBps("BUY", BigDecimal("10000"), bid, ask)!!).isCloseTo(-9.99, within(0.01))
        // 매도: bid보다 싸게 → +, 비싸게 → −
        assertThat(ExecutionQuality.slippageBps("SELL", BigDecimal("9980"), bid, ask)!!).isCloseTo(10.01, within(0.01))
        assertThat(ExecutionQuality.slippageBps("SELL", BigDecimal("10000"), bid, ask)!!).isCloseTo(-10.01, within(0.01))
        // 호가 그대로면 0
        assertThat(ExecutionQuality.slippageBps("BUY", ask, bid, ask)).isEqualTo(0.0)
        assertThat(ExecutionQuality.slippageBps("SELL", bid, bid, ask)).isEqualTo(0.0)
    }

    @Test
    fun `slippage needs the opposite-side quote - a missing or zero quote yields null`() {
        assertThat(ExecutionQuality.slippageBps("BUY", BigDecimal("100"), BigDecimal("99"), null)).isNull()
        assertThat(ExecutionQuality.slippageBps("SELL", BigDecimal("100"), null, BigDecimal("101"))).isNull()
        assertThat(ExecutionQuality.slippageBps("BUY", BigDecimal("100"), null, BigDecimal.ZERO)).isNull()
    }

    @Test
    fun `aggregate is quantity weighted, excludes fills without a quote and reports the count`() {
        val r = ExecutionQuality.aggregate(d, d, listOf(
            row(1, "BUY", "10020", qty = 3),            // +9.99bps × 3
            row(2, "SELL", "10000", qty = 1),           // −10.01bps × 1
            row(3, "BUY", "10020", ask = null),         // 호가 없음(V88 이전) → 제외
            row(4, "SELL", "9980", bid = null, ask = null),
        ))
        assertThat(r.excludedNoQuote).isEqualTo(2)
        assertThat(r.slippage.fillCount).isEqualTo(2)
        assertThat(r.slippage.avgBps!!).isCloseTo((3 * 9.99 - 10.01) / 4, within(0.01))
        assertThat(r.slippageBySide["BUY"]!!.avgBps!!).isCloseTo(9.99, within(0.01))
        assertThat(r.slippageBySide["SELL"]!!.avgBps!!).isCloseTo(-10.01, within(0.01))
        assertThat(r.slippageByOrderType.keys).containsExactly("MARKET")
    }

    @Test
    fun `no fills means null averages, not zero`() {
        val r = ExecutionQuality.aggregate(d, d, emptyList())
        assertThat(r.slippage.avgBps).isNull()
        assertThat(r.slippage.fillCount).isZero()
        assertThat(r.excludedNoQuote).isZero()
        assertThat(r.latency.avgMs).isNull()
        assertThat(r.latency.p95Ms).isNull()
        assertThat(r.latency.orderCount).isZero()
    }

    @Test
    fun `latency counts market orders only, from submit to the first fill, and skips orders without a submit time`() {
        val r = ExecutionQuality.aggregate(d, d, listOf(
            row(1, "BUY", "10010", filledAt = t0.plusMillis(2)),
            row(1, "BUY", "10010", filledAt = t0.plusMillis(50)),        // 같은 주문의 두 번째 체결 — 첫 체결만
            row(2, "BUY", "10010", filledAt = t0.plusMillis(6)),
            row(3, "BUY", "10010", type = "LIMIT", filledAt = t0.plusSeconds(3600)), // 지정가 대기 시간은 지연이 아니다
            row(4, "SELL", "9990", submitted = null),                    // V88 이전
        ))
        assertThat(r.latency.orderCount).isEqualTo(2)
        assertThat(r.latency.avgMs!!).isCloseTo(4.0, within(1e-9))
        assertThat(r.latency.p50Ms).isEqualTo(2.0)
        assertThat(r.latency.p95Ms).isEqualTo(6.0)
        assertThat(r.latency.excludedNoSubmitTime).isEqualTo(1)
        // 지정가 체결은 슬리피지에는 들어간다
        assertThat(r.slippageByOrderType.keys).containsExactlyInAnyOrder("MARKET", "LIMIT")
    }

    @Test
    fun `nearest-rank percentile`() {
        val xs = (1..20).map { it.toDouble() }
        assertThat(ExecutionQuality.percentile(xs, 0.95)).isEqualTo(19.0)
        assertThat(ExecutionQuality.percentile(xs, 0.5)).isEqualTo(10.0)
        assertThat(ExecutionQuality.percentile(listOf(7.0), 0.95)).isEqualTo(7.0)
        assertThat(ExecutionQuality.percentile(emptyList(), 0.5)).isNull()
    }
}
