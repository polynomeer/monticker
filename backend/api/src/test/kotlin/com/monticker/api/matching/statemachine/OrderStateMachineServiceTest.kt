package com.monticker.api.matching.statemachine

import com.monticker.api.matching.statemachine.OrderEvents.CANCEL
import com.monticker.api.matching.statemachine.OrderEvents.COMPLETE_FILL
import com.monticker.api.matching.statemachine.OrderEvents.PARTIAL_FILL
import com.monticker.api.matching.statemachine.OrderEvents.REJECT
import com.monticker.api.matching.statemachine.OrderStates.CANCELLED
import com.monticker.api.matching.statemachine.OrderStates.FILLED
import com.monticker.api.matching.statemachine.OrderStates.PARTIALLY_FILLED
import com.monticker.api.matching.statemachine.OrderStates.PENDING
import com.monticker.api.matching.statemachine.OrderStates.REJECTED
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ADR-103 — spring-statemachine을 순수 전이표로 바꾸기 전에 라이브러리 구현으로 상태×이벤트×남은수량 50조합을 기록했고,
 * 새 구현이 50조합 모두 같은 결과를 낸다(메트릭은 라이브러리의 INITIAL→PENDING 잡음만 빠졌다). 이 테스트가 그 표다.
 */
class OrderStateMachineServiceTest {

    private val registry = SimpleMeterRegistry()
    private val service = OrderStateMachineService(registry)

    /** 허용되는 전이 — (현재, 이벤트, 남은수량) → 다음 상태. 여기에 없는 조합은 전부 예외다. */
    private val allowed = mapOf(
        Triple(PENDING, PARTIAL_FILL, 5) to PARTIALLY_FILLED,
        Triple(PENDING, PARTIAL_FILL, 0) to PENDING,          // 가드 거부 — 예외 없이 현재 상태(라이브러리 동작 유지)
        Triple(PENDING, COMPLETE_FILL, 0) to FILLED,
        Triple(PENDING, COMPLETE_FILL, 5) to FILLED,
        Triple(PARTIALLY_FILLED, COMPLETE_FILL, 0) to FILLED,
        Triple(PARTIALLY_FILLED, COMPLETE_FILL, 5) to FILLED,
        Triple(PENDING, CANCEL, 0) to CANCELLED,
        Triple(PENDING, CANCEL, 5) to CANCELLED,
        Triple(PARTIALLY_FILLED, CANCEL, 0) to CANCELLED,
        Triple(PARTIALLY_FILLED, CANCEL, 5) to CANCELLED,
        Triple(PENDING, REJECT, 0) to REJECTED,
        Triple(PENDING, REJECT, 5) to REJECTED,
    )

    @Test
    fun `상태×이벤트×남은수량 50조합이 라이브러리 시절과 같다`() {
        for (s in OrderStates.entries) for (e in OrderEvents.entries) for (rq in listOf(0, 5)) {
            val expected = allowed[Triple(s, e, rq)]
            if (expected != null) {
                assertThat(service.transition(1L, s, e, rq)).describedAs("$s --[$e]--> rq=$rq").isEqualTo(expected)
            } else {
                assertThatThrownBy { service.transition(1L, s, e, rq) }
                    .describedAs("$s --[$e]--> rq=$rq").isInstanceOf(IllegalStateException::class.java)
            }
        }
    }

    @Test
    fun `실제로 바뀐 전이만 메트릭에 센다`() {
        service.transition(1L, PENDING, PARTIAL_FILL, remainingQty = 5)
        service.transition(1L, PENDING, PARTIAL_FILL, remainingQty = 0)   // 상태 그대로 — 세지 않는다
        runCatching { service.transition(1L, FILLED, CANCEL) }             // 거부 — 세지 않는다

        val counted = registry.meters.filter { it.id.name == "order.state.transition" }
            .associate { "${it.id.getTag("from")}->${it.id.getTag("to")}" to registry.counter(it.id.name, it.id.tags).count() }
        assertThat(counted).isEqualTo(mapOf("PENDING->PARTIALLY_FILLED" to 1.0))
    }
}
