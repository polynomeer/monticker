package com.monticker.api.matching.statemachine

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 주문 상태 전이 규칙. ADR-103 — spring-statemachine(유지보수 종료, Boot 4 미지원)을 같은 전이표의 순수 함수로 바꿨다.
 *
 * 상태 전이:
 *   PENDING ──PARTIAL_FILL──► PARTIALLY_FILLED ──COMPLETE_FILL──► FILLED
 *   PENDING ──COMPLETE_FILL──► FILLED
 *   PENDING / PARTIALLY_FILLED ──CANCEL──► CANCELLED
 *   PENDING ──REJECT──► REJECTED
 *
 * 상태를 저장하지 않는다 — Order 엔티티의 status가 단일 진실 공급원(SSOT)이고, 이 규칙은 호출마다 현재 상태를 받아 다음 상태를 정한다.
 */
object OrderStateTransitions {

    /** 다음 상태. 허용되지 않는 전이면 null. */
    fun next(current: OrderStates, event: OrderEvents, remainingQty: Int): OrderStates? = when (current to event) {
        // 라이브러리 시절 동작을 그대로 둔다: 남은 수량이 없으면 가드가 막지만 예외 없이 현재 상태(PENDING)를 돌려줬다
        // (sendEvent가 가드 거부도 '수락'으로 응답했다). 바꿀지는 호출부와 함께 따로 본다 — ADR-103.
        OrderStates.PENDING to OrderEvents.PARTIAL_FILL ->
            if (remainingQty > 0) OrderStates.PARTIALLY_FILLED else OrderStates.PENDING
        OrderStates.PENDING to OrderEvents.COMPLETE_FILL,
        OrderStates.PARTIALLY_FILLED to OrderEvents.COMPLETE_FILL -> OrderStates.FILLED
        OrderStates.PENDING to OrderEvents.CANCEL,
        OrderStates.PARTIALLY_FILLED to OrderEvents.CANCEL -> OrderStates.CANCELLED
        OrderStates.PENDING to OrderEvents.REJECT -> OrderStates.REJECTED
        else -> null
    }
}

/**
 * 주문 상태 전이를 검증하고 기록하는 파사드. 호출부(MatchingService·LimitOrderFiller·OrderSagaOrchestrator)의 API는 라이브러리 시절과 같다.
 */
@Service
class OrderStateMachineService(
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 현재 상태에서 이벤트를 적용해 전이 후의 상태를 반환한다.
     * 허용되지 않는 전이면 IllegalStateException을 던진다.
     */
    fun transition(
        orderId: Long,
        currentState: OrderStates,
        event: OrderEvents,
        remainingQty: Int = 0,
    ): OrderStates {
        val next = OrderStateTransitions.next(currentState, event, remainingQty)
            ?: throw IllegalStateException("주문 상태 전이 불가: orderId=$orderId $currentState --[$event]--> (blocked)")

        if (next != currentState) {
            // 실제로 일어난 전이만 센다. 라이브러리 시절엔 상태 복원을 위한 재시작 때문에 호출마다 INITIAL→PENDING이
            // 하나씩 더해져(전이 실패 포함) 대시보드의 to=PENDING이 전부 이 잡음이었다 — ADR-103.
            meterRegistry.counter("order.state.transition", "from", currentState.name, "to", next.name).increment()
            log.info("[OrderSM] {} --[{}]--> {} — orderId={}", currentState, event, next, orderId)
        }
        return next
    }
}
