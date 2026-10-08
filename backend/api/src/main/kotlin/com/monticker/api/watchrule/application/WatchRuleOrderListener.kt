package com.monticker.api.watchrule.application

import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * ADR-098 — 주문의 체결·취소를 Watch Rule 발동 기록(PLACED)에 옮긴다.
 *
 * **동기 @EventListener** — 체결·취소와 같은 트랜잭션에서 실행된다(paper.PaperConditionalParentListener와 같은 방식).
 * 체결이 롤백되면 기록 전이도 롤백되고, "체결됐는데 기록은 미체결"인 창이 없다. 전이는 조건부 UPDATE라 재발행에도 멱등이다.
 *
 * 체결은 출처(ADR-085)가 WATCH_RULE인 주문만 본다 — 수동 주문의 체결마다 UPDATE를 돌리지 않는다. 취소 이벤트에는 출처가
 * 없어 모든 취소에 조건부 UPDATE를 한 번 돌린다(미체결 기록만 담는 부분 인덱스 idx_watch_rule_exec_placed_order, V94).
 */
@Component
class WatchRuleOrderListener(private val outcomes: WatchRuleOrderOutcomes) {

    @EventListener
    fun onOrderFilled(event: OrderFilledEvent) {
        if (event.origin != WATCH_RULE_ORIGIN) return
        outcomes.onFilled(event.orderId, event.fillPrice, event.filledAt)
    }

    @EventListener
    fun onOrderCancelled(event: OrderCancelledEvent) {
        outcomes.onCancelled(event.orderId, event.reason, event.cancelledAt)
    }

    companion object {
        /** matching.submit.OrderOriginType.WATCH_RULE의 이름 — 이벤트에는 문자열로 실린다. */
        const val WATCH_RULE_ORIGIN = "WATCH_RULE"
    }
}
