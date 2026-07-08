package com.monticker.api.matching.statemachine

import org.slf4j.LoggerFactory
import org.springframework.statemachine.config.StateMachineFactory
import org.springframework.statemachine.support.DefaultStateMachineContext
import org.springframework.stereotype.Service

/**
 * 주문별 StateMachine 인스턴스를 생성하고 이벤트를 전달하는 파사드.
 *
 * @EnableStateMachineFactory 덕분에 orderId마다 독립 인스턴스를 생성할 수 있다.
 * StateMachine은 요청 스코프에서 일회성으로 사용하며 영속화하지 않는다.
 * 실제 상태는 Order 엔티티의 status 필드가 단일 진실 공급원(SSOT)이다.
 */
@Service
class OrderStateMachineService(
    private val factory: StateMachineFactory<OrderStates, OrderEvents>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 현재 상태에서 이벤트를 전송하고, 전이 후의 상태를 반환한다.
     * 허용되지 않는 전이면 IllegalStateException을 던진다.
     */
    fun transition(
        orderId: Long,
        currentState: OrderStates,
        event: OrderEvents,
        remainingQty: Int = 0,
    ): OrderStates {
        val sm = factory.getStateMachine(orderId.toString())
        sm.stop()

        // 현재 상태를 복원한다 (무상태 서비스 — 매 호출마다 재초기화)
        sm.stateMachineAccessor.doWithAllRegions { access ->
            access.resetStateMachine(
                DefaultStateMachineContext(currentState, null, null, null)
            )
        }

        sm.extendedState.variables["orderId"]      = orderId
        sm.extendedState.variables["remainingQty"] = remainingQty
        sm.start()

        val accepted = sm.sendEvent(
            org.springframework.messaging.support.MessageBuilder
                .withPayload(event)
                .build()
        )

        val newState = sm.state.id
        sm.stop()

        if (!accepted) {
            throw IllegalStateException(
                "주문 상태 전이 불가: orderId=$orderId $currentState --[$event]--> (blocked)"
            )
        }
        log.debug("[OrderSM] orderId={} {} → {}", orderId, currentState, newState)
        return newState
    }
}
