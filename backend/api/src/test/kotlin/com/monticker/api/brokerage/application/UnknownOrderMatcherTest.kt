package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.application.UnknownOrderMatcher.Decision
import com.monticker.api.brokerage.application.UnknownOrderMatcher.Intent
import com.monticker.api.brokerage.infrastructure.BrokerOrderSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class UnknownOrderMatcherTest {

    private val t0 = Instant.parse("2026-10-05T01:00:00Z")
    private val market = Intent("005930", "SELL", 10, limitPrice = null, recordedAt = t0)

    private fun snap(
        id: String = "X", at: Instant = t0.plusSeconds(1), qty: Int = 10, side: String = "SELL",
        symbol: String = "005930", price: BigDecimal? = null,
    ) = BrokerOrderSnapshot(id, null, symbol, side, qty, price, at, "SUBMITTED", 0, null)

    @Test
    fun `조회 실패(null)는 아무것도 단정하지 않는다`() {
        assertThat(UnknownOrderMatcher.decide(market, null, emptySet(), t0.plusSeconds(600))).isEqualTo(Decision.LookupFailed)
    }

    @Test
    fun `종목·방향·수량·시각 창이 맞는 1건이면 매칭`() {
        val d = UnknownOrderMatcher.decide(market, listOf(snap()), emptySet(), t0.plusSeconds(40))
        assertThat(d).isEqualTo(Decision.Matched(snap()))
    }

    @Test
    fun `창 경계 — 5초 전과 60초 후까지는 후보, 그 밖은 아니다`() {
        val inside = listOf(snap("a", t0.minusSeconds(5)), snap("b", t0.plusSeconds(60)))
        assertThat(UnknownOrderMatcher.decide(market, inside, emptySet(), t0.plusSeconds(90))).isEqualTo(Decision.Ambiguous(2))

        val outside = listOf(snap("c", t0.minusSeconds(6)), snap("d", t0.plusSeconds(61)))
        assertThat(UnknownOrderMatcher.decide(market, outside, emptySet(), t0.plusSeconds(90))).isEqualTo(Decision.Wait)
    }

    @Test
    fun `이미 우리 다른 주문에 연결된 증권사 주문번호는 후보가 아니다`() {
        val d = UnknownOrderMatcher.decide(market, listOf(snap("taken"), snap("free")), setOf("taken"), t0.plusSeconds(40))
        assertThat(d).isEqualTo(Decision.Matched(snap("free")))
    }

    @Test
    fun `수량·방향·종목이 다르면 후보가 아니다`() {
        val others = listOf(snap(qty = 9), snap(side = "BUY"), snap(symbol = "000660"))
        assertThat(UnknownOrderMatcher.decide(market, others, emptySet(), t0.plusSeconds(40))).isEqualTo(Decision.Wait)
    }

    @Test
    fun `지정가는 가격까지 같아야 하고, 시장가는 가격을 보지 않는다`() {
        val limit = market.copy(limitPrice = BigDecimal("70000"))
        assertThat(UnknownOrderMatcher.decide(limit, listOf(snap(price = BigDecimal("70000.0000"))), emptySet(), t0.plusSeconds(40)))
            .isInstanceOf(Decision.Matched::class.java)
        assertThat(UnknownOrderMatcher.decide(limit, listOf(snap(price = BigDecimal("69900"))), emptySet(), t0.plusSeconds(40)))
            .isEqualTo(Decision.Wait)
        assertThat(UnknownOrderMatcher.decide(market, listOf(snap(price = BigDecimal("12345"))), emptySet(), t0.plusSeconds(40)))
            .isInstanceOf(Decision.Matched::class.java)
    }

    @Test
    fun `후보 0건 — 2분 전이면 대기(목록 반영 지연), 2분 지나면 미접수`() {
        assertThat(UnknownOrderMatcher.decide(market, emptyList(), emptySet(), t0.plusSeconds(119))).isEqualTo(Decision.Wait)
        assertThat(UnknownOrderMatcher.decide(market, emptyList(), emptySet(), t0.plusSeconds(120))).isEqualTo(Decision.NotFound)
    }
}
