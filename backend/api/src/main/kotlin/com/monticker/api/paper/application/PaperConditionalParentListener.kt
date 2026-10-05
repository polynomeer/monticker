package com.monticker.api.paper.application

import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * ADR-075 "체결 시 자동 등록" — 부모 매칭 주문의 운명을 자식 조건부 주문에 옮긴다.
 *
 * 동기 @EventListener: 체결·취소와 같은 트랜잭션에서 실행된다. 체결이 롤백되면 활성화도 롤백되고,
 * "체결됐는데 손절이 안 걸린" 창이 없다. 두 UPDATE 모두 상태 조건이 있어 재발행에도 멱등이다.
 */
@Component
class PaperConditionalParentListener(private val service: PaperConditionalOrderService) {

    @EventListener
    fun onOrderFilled(event: OrderFilledEvent) {
        service.onParentFilled(event.orderId)
    }

    @EventListener
    fun onOrderCancelled(event: OrderCancelledEvent) {
        service.onParentCancelled(event.orderId)
    }
}
