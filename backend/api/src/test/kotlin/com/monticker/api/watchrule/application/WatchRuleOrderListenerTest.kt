package com.monticker.api.watchrule.application

import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** ADR-098 — 체결·취소 이벤트를 발동 기록 전이로 넘긴다. 전이의 "한 번만"은 통합 테스트(실제 Postgres)가 본다. */
class WatchRuleOrderListenerTest {
    private val outcomes = mockk<WatchRuleOrderOutcomes>(relaxed = true)
    private val listener = WatchRuleOrderListener(outcomes)
    private val at = Instant.parse("2026-10-08T01:00:00Z")

    private fun filled(origin: String?) = OrderFilledEvent(
        orderId = 5L, userId = 1L, stockId = 2L, fillId = 9L, side = "BUY", quantity = 3,
        fillPrice = BigDecimal("990"), amount = BigDecimal("2970"), filledAt = at, origin = origin, originRef = 7L,
    )

    @Test
    fun `a watch rule order fill moves its PLACED record`() {
        listener.onOrderFilled(filled("WATCH_RULE"))
        verify { outcomes.onFilled(5L, BigDecimal("990"), at) }
    }

    @Test
    fun `fills of other origins and legacy events without origin are ignored`() {
        listener.onOrderFilled(filled("MANUAL"))
        listener.onOrderFilled(filled("CONDITIONAL"))
        listener.onOrderFilled(filled(null))
        verify(exactly = 0) { outcomes.onFilled(any(), any(), any()) }
    }

    @Test
    fun `every cancellation is offered with its reason`() {
        listener.onOrderCancelled(OrderCancelledEvent(5L, 1L, 2L, "BUY", BigDecimal("2970"), at, "사용자 취소"))
        verify { outcomes.onCancelled(5L, "사용자 취소", at) }
    }
}
