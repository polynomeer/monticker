package com.monticker.api.matching.statemachine

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.statemachine.StateContext
import org.springframework.statemachine.action.Action
import org.springframework.statemachine.config.EnableStateMachineFactory
import org.springframework.statemachine.config.StateMachineConfigurerAdapter
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer
import org.springframework.statemachine.guard.Guard
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import org.springframework.statemachine.state.State
import java.util.EnumSet

/**
 * 주문 라이프사이클 StateMachine 설정.
 *
 * 상태 전이:
 *   PENDING ──PARTIAL_FILL──► PARTIALLY_FILLED ──COMPLETE_FILL──► FILLED
 *   PENDING ──COMPLETE_FILL──► FILLED
 *   PENDING / PARTIALLY_FILLED ──CANCEL──► CANCELLED
 *   PENDING ──REJECT──► REJECTED
 *
 * @EnableStateMachineFactory: 주문마다 독립 인스턴스 생성 (per-order scope).
 */
@Configuration
@EnableStateMachineFactory
class OrderStateMachineConfig(
    private val meterRegistry: MeterRegistry,
) : StateMachineConfigurerAdapter<OrderStates, OrderEvents>() {

    private val log = LoggerFactory.getLogger(javaClass)

    // ── 1. 리스너 — 상태 전이를 Micrometer 카운터로 기록 ─────────────────────
    override fun configure(config: StateMachineConfigurationConfigurer<OrderStates, OrderEvents>) {
        config.withConfiguration()
            .autoStartup(true)
            .listener(object : StateMachineListenerAdapter<OrderStates, OrderEvents>() {
                override fun stateChanged(
                    from: State<OrderStates, OrderEvents>?,
                    to: State<OrderStates, OrderEvents>,
                ) {
                    val fromName = from?.id?.name ?: "INITIAL"
                    val toName   = to.id.name
                    meterRegistry.counter(
                        "order.state.transition",
                        "from", fromName, "to", toName,
                    ).increment()
                    log.debug("[OrderSM] {} → {}", fromName, toName)
                }
            })
    }

    // ── 2. 상태 정의 ──────────────────────────────────────────────────────────
    override fun configure(states: StateMachineStateConfigurer<OrderStates, OrderEvents>) {
        states.withStates()
            .initial(OrderStates.PENDING)
            .states(EnumSet.allOf(OrderStates::class.java))
            .end(OrderStates.FILLED)
            .end(OrderStates.CANCELLED)
            .end(OrderStates.REJECTED)
    }

    // ── 3. 전이 규칙 ──────────────────────────────────────────────────────────
    override fun configure(transitions: StateMachineTransitionConfigurer<OrderStates, OrderEvents>) {
        transitions
            // 일부 체결
            .withExternal()
                .source(OrderStates.PENDING).target(OrderStates.PARTIALLY_FILLED)
                .event(OrderEvents.PARTIAL_FILL)
                .guard(hasRemainingQtyGuard())
                .action(logTransitionAction("PARTIAL_FILL"))
                .and()
            // 전량 체결 (PENDING에서 바로)
            .withExternal()
                .source(OrderStates.PENDING).target(OrderStates.FILLED)
                .event(OrderEvents.COMPLETE_FILL)
                .action(logTransitionAction("COMPLETE_FILL_FROM_PENDING"))
                .and()
            // 전량 체결 (일부 체결 후)
            .withExternal()
                .source(OrderStates.PARTIALLY_FILLED).target(OrderStates.FILLED)
                .event(OrderEvents.COMPLETE_FILL)
                .action(logTransitionAction("COMPLETE_FILL_FROM_PARTIAL"))
                .and()
            // PENDING 취소
            .withExternal()
                .source(OrderStates.PENDING).target(OrderStates.CANCELLED)
                .event(OrderEvents.CANCEL)
                .action(logTransitionAction("CANCEL_FROM_PENDING"))
                .and()
            // PARTIALLY_FILLED 취소
            .withExternal()
                .source(OrderStates.PARTIALLY_FILLED).target(OrderStates.CANCELLED)
                .event(OrderEvents.CANCEL)
                .action(logTransitionAction("CANCEL_FROM_PARTIAL"))
                .and()
            // 거부 (리스크 차단, 잔고 부족)
            .withExternal()
                .source(OrderStates.PENDING).target(OrderStates.REJECTED)
                .event(OrderEvents.REJECT)
                .action(logTransitionAction("REJECT"))
    }

    // ── Guards ────────────────────────────────────────────────────────────────
    @Bean
    fun hasRemainingQtyGuard(): Guard<OrderStates, OrderEvents> = Guard { ctx ->
        val remaining = ctx.extendedState.variables["remainingQty"] as? Int ?: 0
        remaining > 0
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    @Bean
    fun logTransitionAction(label: String): Action<OrderStates, OrderEvents> = Action { ctx ->
        val orderId = ctx.extendedState.variables["orderId"]
        log.info("[OrderSM] {} — orderId={}", label, orderId)
    }
}
