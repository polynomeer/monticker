package com.monticker.api.quant.application

import com.monticker.api.matching.events.OrderFilledEvent
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 주문 체결 이벤트를 수신해 quant 모듈의 후속 처리를 수행하는 리스너.
 *
 * quant 모듈은 matching 모듈을 직접 의존하지 않는다.
 * Spring Modulith 이벤트 채널을 통해 느슨하게 결합된다.
 *
 * 현재는 체결 이벤트를 로깅한다.
 * 향후 RuleSet 실전 검증(live tracking) 기능 추가 시 이 리스너를 확장한다.
 */
@Component
class OrderFilledStrategyListener {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderFilled(event: OrderFilledEvent) {
        log.info("[Quant] OrderFilledEvent: userId={} stockId={} side={} qty={} price={}",
            event.userId, event.stockId, event.side, event.quantity, event.fillPrice)
    }
}
