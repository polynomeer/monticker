package com.monticker.api.paper.application

import com.monticker.api.watchrule.application.WatchRuleExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 거래 내역 "경로" — 매칭 주문 멱등 키에서 읽는다. 키를 만드는 쪽과 형식이 어긋나면 이 테스트가 깨진다. */
class TradeRouteTest {
    @Test
    fun `watch rule key carries the rule id`() {
        val r = TradeRoute.of(WatchRuleExecutor.idempotencyKey(12L, 345L))
        assertThat(r.source).isEqualTo("WATCH_RULE")
        assertThat(r.watchRuleId).isEqualTo(12L)
    }

    @Test
    fun `conditional order key carries the conditional order id`() {
        val r = TradeRoute.of("PCO:77")
        assertThat(r.source).isEqualTo("CONDITIONAL")
        assertThat(r.conditionalOrderId).isEqualTo(77L)
    }

    @Test
    fun `no key or an unknown key is a manual trade`() {
        assertThat(TradeRoute.of(null).source).isEqualTo("MANUAL")
        assertThat(TradeRoute.of("something").source).isEqualTo("MANUAL")
        assertThat(TradeRoute.of("WR:x:1").watchRuleId).isNull()
    }
}
